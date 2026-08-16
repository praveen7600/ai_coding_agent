package com.praveen.aicodingagent.orchestrator.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A shared, proactive budget that every GeminiClient call draws a permit
 * from before it's allowed to hit the network - not a reaction to 429s,
 * a way to stop causing them in the first place.
 *
 * GeminiClient's own retry loop is reactive and per-call: it only starts
 * paying attention to the quota once a single call has already been
 * rejected. That's fine for one task running alone, but AsyncConfig runs
 * up to 4 orchestrator loops concurrently (agentTaskExecutor), and each
 * loop fires its own generateContent calls with no coordination between
 * them. Four threads independently unaware of each other can burst past
 * the free tier's ~10-15 RPM ceiling in the time it takes to trigger a
 * couple of tasks in a row while testing - exactly the "even small tasks
 * get rate limited" symptom this exists to fix.
 *
 * Implementation is a fixed-size Semaphore refilled to capacity once a
 * minute, rather than a token-bucket library: the whole thing is ~20
 * lines, has no new dependency to fetch (this sandbox can't reach Maven
 * Central to verify one), and the semantics needed here - "at most N
 * permits outstanding per rolling-ish minute, block callers past that
 * instead of rejecting them" - don't need anything fancier. A full token
 * bucket with sub-minute granularity would smooth bursts better, but a
 * single developer manually triggering tasks doesn't need that
 * precision.
 */
@Component
@Slf4j
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiRateLimiter {

    /**
     * Bounded, not unbounded: a caller stuck waiting for a permit that
     * will genuinely never come (misconfigured limit of 0 slipping past
     * the GeminiProperties default, or every permit wedged some other
     * way) should surface as a clear failure, not hang the orchestrator
     * thread - and by extension the agentTaskExecutor pool slot - forever.
     */
    private static final long DEFAULT_ACQUIRE_TIMEOUT_SECONDS = 150;

    private final int maxRequestsPerMinute;
    private final Semaphore permits;
    private final long acquireTimeoutSeconds;

    public GeminiRateLimiter(GeminiProperties properties) {
        // Generous relative to the 1-minute refill window: a caller can
        // wait through a couple of refill cycles before this gives up,
        // which matters when several queued orchestrator iterations are
        // all waiting on the same budget.
        this(properties, DEFAULT_ACQUIRE_TIMEOUT_SECONDS);
    }

    /**
     * Package-private: lets tests use a short timeout instead of waiting
     * out the real 150s one to prove the "gives up eventually" behavior.
     * Production code always goes through the single-arg constructor.
     */
    GeminiRateLimiter(GeminiProperties properties, long acquireTimeoutSeconds) {
        this.maxRequestsPerMinute = properties.maxRequestsPerMinute();
        this.permits = new Semaphore(maxRequestsPerMinute);
        this.acquireTimeoutSeconds = acquireTimeoutSeconds;
    }

    /**
     * Blocks the calling thread until a permit is available or the
     * timeout elapses. Called from GeminiClient.generate() before the
     * HTTP call is made - not after a 429, before the request that would
     * have caused one.
     */
    public void acquire() {
        try {
            boolean acquired = permits.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LlmClientException(
                        "Timed out after " + acquireTimeoutSeconds
                                + "s waiting for a Gemini request slot (limit: "
                                + maxRequestsPerMinute + "/min). The free-tier quota is likely "
                                + "exhausted for longer than a per-minute window can absorb.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("Interrupted while waiting for a Gemini request slot", e);
        }
    }

    /**
     * Tops the semaphore back up to its configured capacity every minute.
     * Not a true sliding window - permits acquired just before a refill
     * and again just after it can, in the worst case, let slightly more
     * than maxRequestsPerMinute calls through inside a single wall-clock
     * minute. Acceptable here: the goal is keeping a single developer's
     * concurrent tasks comfortably under the quota, not billing-grade
     * precision, and the configured default already leaves margin below
     * Gemini's actual ceiling.
     */
    @Scheduled(fixedRate = 60_000)
    public void refill() {
        int deficit = maxRequestsPerMinute - permits.availablePermits();
        if (deficit > 0) {
            permits.release(deficit);
            log.debug("Gemini rate limiter refilled: released {} permit(s)", deficit);
        }
    }
}
