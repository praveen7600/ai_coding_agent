package com.praveen.aicodingagent.orchestrator.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * apiKey MUST come from an environment variable in application.yml
 * (${GEMINI_API_KEY}), never hardcoded - same rule as jwt.secret. model is
 * separated out (rather than hardcoded in GeminiClient) so swapping to a
 * newer Gemini model, or pointing a test at a cheaper one, is a config
 * change, not a code change.
 *
 * maxRequestsPerMinute bounds GeminiRateLimiter, the shared budget every
 * orchestrator thread draws from - see that class's javadoc for why a
 * per-call 429 retry alone isn't enough once AsyncConfig lets several
 * tasks run concurrently. Defaults to 8 (a safety margin under the free
 * tier's ~10-15 RPM ceiling) when unset or non-positive, so existing
 * application.yml files without this key don't need to change.
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String model,
        int requestTimeoutSeconds,
        int maxRequestsPerMinute
) {
    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 8;

    public int maxRequestsPerMinute() {
        return maxRequestsPerMinute > 0 ? maxRequestsPerMinute : DEFAULT_MAX_REQUESTS_PER_MINUTE;
    }
}
