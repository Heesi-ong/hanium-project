package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerDistributedLockTest {

    private final StringRedisTemplate redisTemplate = mock(
            StringRedisTemplate.class,
            RETURNS_DEEP_STUBS
    );
    private final ValueOperations<String, String> valueOperations = redisTemplate.opsForValue();
    private final SchedulerDistributedLock schedulerDistributedLock = new SchedulerDistributedLock(redisTemplate);

    @Test
    void tryLockReturnsTrueWhenRedisSetIfAbsentSucceeds() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("scheduler-lock:storage-cleanup"),
                anyString(),
                eq(Duration.ofMinutes(10))
        )).thenReturn(true);

        boolean acquired = schedulerDistributedLock.tryLock("storage-cleanup", Duration.ofMinutes(10));

        assertThat(acquired).isTrue();
    }

    @Test
    void tryLockReturnsFalseWhenLockAlreadyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("scheduler-lock:storage-cleanup"),
                anyString(),
                eq(Duration.ofMinutes(10))
        )).thenReturn(false);

        boolean acquired = schedulerDistributedLock.tryLock("storage-cleanup", Duration.ofMinutes(10));

        assertThat(acquired).isFalse();
    }

    @Test
    void tryLockFallsBackToTrueWhenRedisFails() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        boolean acquired = schedulerDistributedLock.tryLock("storage-cleanup", Duration.ofMinutes(10));

        assertThat(acquired).isTrue();
    }
}
