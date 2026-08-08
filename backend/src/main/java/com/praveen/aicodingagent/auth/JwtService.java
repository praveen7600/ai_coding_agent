package com.praveen.aicodingagent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless JWT only - no refresh tokens, no server-side session/blacklist
 * in V1 (see ADR-0004). A token is valid until it expires; there is
 * currently no way to revoke one early (e.g. on logout or password change).
 * That's a real, documented gap, not an oversight.
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secret()));
        this.expirationMinutes = properties.expirationMinutes();
    }

    public String generateToken(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public Instant expirationOf(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /** Returns the authenticated user's id, or empty if the token is missing/expired/malformed/tampered. */
    public UUID validateAndGetUserId(String token) {
        Claims claims = parseClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid or expired token", e);
        }
    }
}
