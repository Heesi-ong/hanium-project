package com.hanium.presentation.global.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
public class VideoAccessTokenProvider {

    private static final String PURPOSE = "video-access";
    private static final Duration EXPIRATION = Duration.ofMinutes(5);

    private final SecretKey signingKey;

    public VideoAccessTokenProvider(
            @Value("${security.jwt.secret:presentation-coaching-local-jwt-secret-change-me-2026}") String secret
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueToken(String jobId, Long ownerId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(EXPIRATION);

        return Jwts.builder()
                .claim("jobId", jobId)
                .claim("ownerId", ownerId)
                .claim("purpose", PURPOSE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public Optional<VideoAccessClaims> validate(String token, String expectedJobId) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String purpose = claims.get("purpose", String.class);
            String jobId = claims.get("jobId", String.class);
            Long ownerId = resolveOwnerId(claims.get("ownerId"));

            if (!PURPOSE.equals(purpose) || !expectedJobId.equals(jobId) || ownerId == null) {
                return Optional.empty();
            }

            return Optional.of(new VideoAccessClaims(jobId, ownerId));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Long resolveOwnerId(Object ownerId) {
        if (ownerId instanceof Number number) {
            return number.longValue();
        }

        if (ownerId instanceof String stringValue) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        return null;
    }

    public record VideoAccessClaims(
            String jobId,
            Long ownerId
    ) {
    }
}
