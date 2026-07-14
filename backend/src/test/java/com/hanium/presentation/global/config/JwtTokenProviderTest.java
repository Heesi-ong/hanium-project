package com.hanium.presentation.global.config;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.global.config.SecurityConfig.JwtTokenProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-that-is-long-enough-for-hs256-2026";

    @Test
    void extractsIssuedAtFromCreatedToken() {
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET, Duration.ofMinutes(30));
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getEmail()).thenReturn("issued-at@example.com");

        Instant before = Instant.now().minusSeconds(1);
        String token = jwtTokenProvider.createToken(user);
        Instant after = Instant.now().plusSeconds(1);

        assertThat(jwtTokenProvider.extractIssuedAt(token))
                .isPresent()
                .get()
                .satisfies(issuedAt -> assertThat(issuedAt).isBetween(before, after));
    }

    @Test
    void extractIssuedAtReturnsEmptyForInvalidToken() {
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET, Duration.ofMinutes(30));

        assertThat(jwtTokenProvider.extractIssuedAt("not-a-jwt")).isEmpty();
    }
}
