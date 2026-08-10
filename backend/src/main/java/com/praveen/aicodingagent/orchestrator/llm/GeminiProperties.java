package com.praveen.aicodingagent.orchestrator.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * apiKey MUST come from an environment variable in application.yml
 * (${GEMINI_API_KEY}), never hardcoded - same rule as jwt.secret. model is
 * separated out (rather than hardcoded in GeminiClient) so swapping to a
 * newer Gemini model, or pointing a test at a cheaper one, is a config
 * change, not a code change.
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String model,
        int requestTimeoutSeconds
) {
}
