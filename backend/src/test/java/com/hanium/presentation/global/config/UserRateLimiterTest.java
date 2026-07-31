package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserRateLimiterTest {

    @Test
    void localFallbackReservesWeightedPermitsWithoutExceedingCapacity() {
        UserRateLimiter limiter = createLimiterWithUnavailableRedis(5);

        assertThat(limiter.tryConsume("video-llm-monthly", "2026-07", 3)).isTrue();
        assertThat(limiter.tryConsume("video-llm-monthly", "2026-07", 3)).isFalse();
        assertThat(limiter.tryConsume("video-llm-monthly", "2026-07", 2)).isTrue();
        assertThat(limiter.tryConsume("video-llm-monthly", "2026-07", 1)).isFalse();
        assertThat(limiter.getCurrentCount("video-llm-monthly", "2026-07"))
                .isEqualTo(5);
    }

    @Test
    void rejectsNonPositivePermitCount() {
        UserRateLimiter limiter = createLimiterWithUnavailableRedis(5);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.tryConsume(
                        "video-llm-monthly",
                        "2026-07",
                        0
                ))
                .withMessageContaining("permits");
    }

    private UserRateLimiter createLimiterWithUnavailableRedis(int capacity) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any(Object[].class)
        ))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        RateLimitProperties properties = mock(RateLimitProperties.class);
        when(properties.videoLlmMonthly())
                .thenReturn(new RateLimitProperties.Limit(capacity, 60));

        return new UserRateLimiter(redisTemplate, properties);
    }
}
