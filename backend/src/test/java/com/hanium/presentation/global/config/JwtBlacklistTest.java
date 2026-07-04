package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtBlacklistTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final JwtBlacklist jwtBlacklist = new JwtBlacklist(redisTemplate);

    @Test
    void blacklistStoresTokenWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        jwtBlacklist.blacklist("access-token", Duration.ofMinutes(30));

        verify(valueOperations).set(anyString(), eq("true"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void isBlacklistedReturnsTrueWhenKeyExists() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isTrue();
    }

    @Test
    void isBlacklistedReturnsFalseWhenKeyDoesNotExist() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isFalse();
    }

    @Test
    void isBlacklistedFallsBackToFalseWhenRedisFails() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isFalse();
    }
}
