package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Test
    void localFallbackRejectsMonthlyBudgetWithoutConsumingDailyPermit() {
        UserRateLimiter limiter = createLimiterWithUnavailableRedis(2, 3);

        assertThat(limiter.reserveVideoLlmBudget(7L, "2026-08", 3))
                .isEqualTo(UserRateLimiter.VideoLlmBudgetReservation.RESERVED);
        assertThat(limiter.reserveVideoLlmBudget(7L, "2026-08", 1))
                .isEqualTo(UserRateLimiter.VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED);

        assertThat(limiter.getCurrentCount("video-llm-daily", "user:7")).isEqualTo(1);
        assertThat(limiter.getCurrentCount("video-llm-monthly", "2026-08")).isEqualTo(3);
    }

    @Test
    void concurrentLocalFallbackReservationsNeverPartiallyConsumeEitherBudget() throws Exception {
        UserRateLimiter limiter = createLimiterWithUnavailableRedis(5, 100);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<UserRateLimiter.VideoLlmBudgetReservation>> futures = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return limiter.reserveVideoLlmBudget(7L, "2026-08", 3);
                }));
            }

            start.countDown();
            long reserved = 0;
            for (Future<UserRateLimiter.VideoLlmBudgetReservation> future : futures) {
                if (future.get() == UserRateLimiter.VideoLlmBudgetReservation.RESERVED) {
                    reserved++;
                }
            }

            assertThat(reserved).isEqualTo(5);
            assertThat(limiter.getCurrentCount("video-llm-daily", "user:7")).isEqualTo(5);
            assertThat(limiter.getCurrentCount("video-llm-monthly", "2026-08")).isEqualTo(15);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void redisAtomicScriptResultCodesMapToReservationOutcome() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any(Object[].class)
        )).thenReturn(0L, 1L, 2L);

        RateLimitProperties properties = mock(RateLimitProperties.class);
        when(properties.videoLlmDaily()).thenReturn(new RateLimitProperties.Limit(10, 60));
        when(properties.videoLlmMonthly()).thenReturn(new RateLimitProperties.Limit(100, 120));
        UserRateLimiter limiter = new UserRateLimiter(redisTemplate, properties);

        assertThat(limiter.reserveVideoLlmBudget(7L, "2026-08", 3))
                .isEqualTo(UserRateLimiter.VideoLlmBudgetReservation.RESERVED);
        assertThat(limiter.reserveVideoLlmBudget(7L, "2026-08", 3))
                .isEqualTo(UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED);
        assertThat(limiter.reserveVideoLlmBudget(7L, "2026-08", 3))
                .isEqualTo(UserRateLimiter.VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED);
    }

    private UserRateLimiter createLimiterWithUnavailableRedis(int capacity) {
        return createLimiterWithUnavailableRedis(10, capacity);
    }

    private UserRateLimiter createLimiterWithUnavailableRedis(int dailyCapacity, int monthlyCapacity) {
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
        when(properties.videoLlmDaily())
                .thenReturn(new RateLimitProperties.Limit(dailyCapacity, 60));
        when(properties.videoLlmMonthly())
                .thenReturn(new RateLimitProperties.Limit(monthlyCapacity, 60));

        return new UserRateLimiter(redisTemplate, properties);
    }
}
