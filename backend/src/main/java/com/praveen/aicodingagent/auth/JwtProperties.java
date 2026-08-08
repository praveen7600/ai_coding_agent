package com.praveen.aicodingagent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,          // Base64-encoded HMAC-SHA256 key. MUST be overridden per environment.
        long expirationMinutes
) {
}
