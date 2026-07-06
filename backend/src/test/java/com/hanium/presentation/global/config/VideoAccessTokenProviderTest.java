package com.hanium.presentation.global.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VideoAccessTokenProviderTest {

    private static final String SECRET = "presentation-coaching-local-jwt-secret-change-me-2026";
    private static final String JOB_ID = "20260707090000-aaaaaaaa";
    private static final Long OWNER_ID = 1L;

    private final VideoAccessTokenProvider provider = new VideoAccessTokenProvider(SECRET);

    @Test
    void issueTokenCanBeValidatedForSameJobId() {
        String token = provider.issueToken(JOB_ID, OWNER_ID);

        Optional<VideoAccessTokenProvider.VideoAccessClaims> claims = provider.validate(token, JOB_ID);

        assertThat(claims).isPresent();
        assertThat(claims.get().jobId()).isEqualTo(JOB_ID);
        assertThat(claims.get().ownerId()).isEqualTo(OWNER_ID);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = createToken(
                JOB_ID,
                OWNER_ID,
                "video-access",
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(300)
        );

        assertThat(provider.validate(token, JOB_ID)).isEmpty();
    }

    @Test
    void tokenForDifferentJobIdIsRejected() {
        String token = provider.issueToken(JOB_ID, OWNER_ID);

        assertThat(provider.validate(token, "20260707090001-bbbbbbbb")).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = provider.issueToken(JOB_ID, OWNER_ID);
        String replacement = token.endsWith("a") ? "b" : "a";
        String tamperedToken = token.substring(0, token.length() - 1) + replacement;

        assertThat(provider.validate(tamperedToken, JOB_ID)).isEmpty();
    }

    private String createToken(
            String jobId,
            Long ownerId,
            String purpose,
            Instant issuedAt,
            Instant expiresAt
    ) {
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .claim("jobId", jobId)
                .claim("ownerId", ownerId)
                .claim("purpose", purpose)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }
}
