package com.praveen.aicodingagent.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    // 256-bit base64 key, test-only.
    private static final JwtProperties PROPERTIES =
            new JwtProperties("dGhpc2lzYWRldm9ubHlzZWNyZXRrZXlkb25vdHVzZWlucHJvZA==", 60);

    private final JwtService jwtService = new JwtService(PROPERTIES);

    @Test
    void issuedTokenRoundTripsToTheSameUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "praveen@example.com");

        assertThat(jwtService.validateAndGetUserId(token)).isEqualTo(userId);
    }

    @Test
    void rejectsTamperedToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "praveen@example.com");
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtService.validateAndGetUserId(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() {
        JwtProperties otherKey = new JwtProperties("YW5vdGhlcmRldm9ubHlzZWNyZXRrZXlmb3J0ZXN0aW5n", 60);
        JwtService otherService = new JwtService(otherKey);
        String token = otherService.generateToken(UUID.randomUUID(), "someone@example.com");

        assertThatThrownBy(() -> jwtService.validateAndGetUserId(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtProperties alreadyExpired = new JwtProperties(PROPERTIES.secret(), 0);
        JwtService expiringService = new JwtService(alreadyExpired);
        String token = expiringService.generateToken(UUID.randomUUID(), "someone@example.com");

        assertThatThrownBy(() -> jwtService.validateAndGetUserId(token))
                .isInstanceOf(InvalidTokenException.class);
    }
}
