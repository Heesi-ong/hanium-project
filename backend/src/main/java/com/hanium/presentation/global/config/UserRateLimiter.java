package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UserRateLimiter.class);
    private static final String KEY_PREFIX = "rate-limit:";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties rateLimitProperties;
    private final Map<String, LocalWindow> localFallbackWindows = new ConcurrentHashMap<>();

    private volatile boolean redisWarningLogged = false;

    public UserRateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimitProperties rateLimitProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.rateLimitProperties = rateLimitProperties;
    }

    public boolean tryConsume(String bucketName, Long userId) {
        RateLimitProperties.Limit limit = resolveLimit(bucketName);
        validateLimit(bucketName, limit);

        String key = buildKey(bucketName, userId);

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(limit.refillMinutes()));
            }

            onRedisSuccess();
            return count != null && count <= limit.capacity();
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
            return tryConsumeLocalFallback(key, limit);
        }
    }

    public void resetForTest() {
        localFallbackWindows.clear();

        try {
            redisTemplate.delete(redisTemplate.keys(KEY_PREFIX + "*"));
        } catch (RuntimeException ignored) {
            // 테스트 편의를 위한 초기화 메서드입니다. Redis가 없어도 fallback 카운터만 지워지면 됩니다.
        }
    }

    private RateLimitProperties.Limit resolveLimit(String bucketName) {
        return switch (bucketName) {
            case "upload" -> rateLimitProperties.upload();
            case "analysis" -> rateLimitProperties.analysis();
            default -> throw new IllegalArgumentException("Unknown rate limit bucket: " + bucketName);
        };
    }

    private void validateLimit(String bucketName, RateLimitProperties.Limit limit) {
        if (limit == null || limit.capacity() < 1 || limit.refillMinutes() < 1) {
            throw new IllegalStateException("Invalid rate limit configuration: " + bucketName);
        }
    }

    private boolean tryConsumeLocalFallback(String key, RateLimitProperties.Limit limit) {
        Instant now = Instant.now();
        LocalWindow window = localFallbackWindows.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.expiresAt())) {
                return new LocalWindow(1, now.plus(Duration.ofMinutes(limit.refillMinutes())));
            }

            return new LocalWindow(current.count() + 1, current.expiresAt());
        });

        return window.count() <= limit.capacity();
    }

    private void onRedisFailure(RuntimeException exception) {
        if (!redisWarningLogged) {
            redisWarningLogged = true;
            log.warn(
                    "Redis rate limit 카운터 사용 실패 - 현재 backend 인스턴스의 로컬 카운터로 대체합니다. 원인: {}",
                    exception.toString()
            );
        }
    }

    private void onRedisSuccess() {
        if (redisWarningLogged) {
            redisWarningLogged = false;
            log.info("Redis rate limit 카운터 연결이 복구되었습니다.");
        }
    }

    private String buildKey(String bucketName, Long userId) {
        return KEY_PREFIX + bucketName + ":user:" + userId;
    }

    private record LocalWindow(
            int count,
            Instant expiresAt
    ) {
    }
}
