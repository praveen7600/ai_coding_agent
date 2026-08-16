package com.praveen.aicodingagent.orchestrator.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the two behaviors this class exists for: it lets calls through up
 * to the configured cap, and - the whole point, since AsyncConfig can run
 * several orchestrator loops at once - it makes concurrent callers wait
 * for each other rather than each independently assuming they have the
 * full quota to themselves.
 *
 * Does not test the @Scheduled refill directly (that would mean a real
 * 60-second sleep, or reflection into Spring's scheduler for no real
 * gain); refill() is called directly instead, which exercises the same
 * logic without the wall-clock wait.
 */
class GeminiRateLimiterTest {

    @Test
    void allowsCallsUpToTheConfiguredLimit() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(properties(3));

        // Three permits available - none of these should block.
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
    }

    @Test
    void blocksAConcurrentCallerUntilAPermitIsFreed() throws InterruptedException {
        GeminiRateLimiter limiter = new GeminiRateLimiter(properties(1));
        limiter.acquire(); // consume the only permit up front

        AtomicInteger acquiredAfterRefill = new AtomicInteger(0);
        CountDownLatch waitingStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                waitingStarted.countDown();
                limiter.acquire(); // should block until refill() below runs
                acquiredAfterRefill.incrementAndGet();
            });

            assertThat(waitingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            // Give the other thread a moment to actually reach acquire()
            // and start blocking before we refill, so this test would
            // fail (not pass vacuously) if acquire() didn't block at all.
            Thread.sleep(200);
            assertThat(acquiredAfterRefill.get()).isZero();

            limiter.refill();

            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(acquiredAfterRefill.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refillNeverExceedsTheConfiguredCap() {
        // 1-second timeout instead of the real 150s - this test's whole
        // point is confirming acquire() gives up once genuinely starved,
        // so it needs to actually reach that timeout without slowing the
        // suite down.
        GeminiRateLimiter limiter = new GeminiRateLimiter(properties(2), 1);

        // Refilling without ever acquiring must not push the available
        // permits above the configured cap of 2.
        limiter.refill();
        limiter.refill();

        limiter.acquire();
        limiter.acquire();
        // Third call has nothing left to draw from and should time out
        // (not hang, not silently proceed) rather than exceed the cap.
        assertThatThrownBy(limiter::acquire)
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("Timed out");
    }

    private GeminiProperties properties(int maxRequestsPerMinute) {
        return new GeminiProperties("test-key", "gemini-2.0-flash", 60, maxRequestsPerMinute);
    }
}
