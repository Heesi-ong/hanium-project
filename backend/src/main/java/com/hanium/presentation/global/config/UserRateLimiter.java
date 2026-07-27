package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class UserRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UserRateLimiter.class);
    private static final String KEY_PREFIX = "rate-limit:";

    // INCR과 "처음 생성됐을 때만 EXPIRE"를 각각 별도의 Redis 왕복으로 하면, INCR은
    // 성공했는데 그 직후 EXPIRE만 실패하는 경우(네트워크 순간 단절 등) 카운터가
    // TTL 없이 영구히 남는다. 그러면 이후 어떤 요청도 count==1 분기를 다시 타지 못해
    // 그 키는 영원히 만료되지 않고, 해당 사용자/버킷은 다음 초기화 시점 없이 영구히
    // 차단된다. Lua 스크립트로 INCR+EXPIRE를 하나의 원자적 왕복으로 묶어 이 경합을
    // 없앤다(Redis는 스크립트 실행 중 다른 명령을 끼워 넣지 않는다).
    private static final RedisScript<Long> INCREMENT_AND_EXPIRE_IF_NEW_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return current",
            Long.class
    );

    // 공급자 비용처럼 한 번에 여러 단위를 예약하는 버킷은 용량을 넘는 요청을 카운터에
    // 더하면 안 된다. GET+판정+INCRBY를 Lua 한 번으로 묶어 동시 요청도 capacity를 넘지
    // 못하게 한다. 기존 단일 API rate limit은 거절 시도도 사용량에 포함하는 계약이므로
    // 위의 별도 스크립트를 계속 사용한다.
    private static final RedisScript<Long> RESERVE_WITHIN_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local current = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "local permits = tonumber(ARGV[2]) "
                    + "local capacity = tonumber(ARGV[3]) "
                    + "if current + permits > capacity then return -1 end "
                    + "local next = redis.call('INCRBY', KEYS[1], permits) "
                    + "if current == 0 then "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return next",
            Long.class
    );

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
        return tryConsumeSingle(bucketName, "user:" + userId);
    }

    public boolean tryConsume(String bucketName, String key) {
        return tryConsumeSingle(bucketName, key);
    }

    public boolean tryConsume(String bucketName, Long userId, int permits) {
        return tryConsume(bucketName, "user:" + userId, permits);
    }

    public boolean tryConsume(String bucketName, String key, int permits) {
        RateLimitProperties.Limit limit = resolveLimit(bucketName);
        validateLimit(bucketName, limit);
        validatePermits(permits);

        String rateLimitKey = buildKey(bucketName, key);

        try {
            Long count = redisTemplate.execute(
                    RESERVE_WITHIN_LIMIT_SCRIPT,
                    Collections.singletonList(rateLimitKey),
                    String.valueOf(Duration.ofMinutes(limit.refillMinutes()).toSeconds()),
                    String.valueOf(permits),
                    String.valueOf(limit.capacity())
            );

            onRedisSuccess();
            return count != null && count >= 0;
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
            return tryConsumeLocalFallback(rateLimitKey, limit, permits);
        }
    }

    private boolean tryConsumeSingle(String bucketName, String key) {
        RateLimitProperties.Limit limit = resolveLimit(bucketName);
        validateLimit(bucketName, limit);

        String rateLimitKey = buildKey(bucketName, key);

        try {
            Long count = redisTemplate.execute(
                    INCREMENT_AND_EXPIRE_IF_NEW_SCRIPT,
                    Collections.singletonList(rateLimitKey),
                    String.valueOf(Duration.ofMinutes(limit.refillMinutes()).toSeconds())
            );

            onRedisSuccess();
            return count != null && count <= limit.capacity();
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
            return tryConsumeSingleLocalFallback(rateLimitKey, limit);
        }
    }

    // 여러 예산(예: 일일/월간)을 순서대로 확인해야 할 때, tryConsume으로 먼저
    // 소비해버리면 뒤쪽 예산이 이미 소진된 경우에도 앞쪽 예산을 헛되이 낭비하게
    // 된다. wouldAllow는 실제로 소비하지 않고 "지금 소비하면 허용될지"만 확인해,
    // 모든 예산을 먼저 peek한 뒤 실제 소비는 전부 통과했을 때만 하도록 한다.
    public boolean wouldAllow(String bucketName, Long userId) {
        return wouldAllow(bucketName, "user:" + userId, 1);
    }

    public boolean wouldAllow(String bucketName, String key) {
        return wouldAllow(bucketName, key, 1);
    }

    public boolean wouldAllow(String bucketName, Long userId, int permits) {
        return wouldAllow(bucketName, "user:" + userId, permits);
    }

    public boolean wouldAllow(String bucketName, String key, int permits) {
        RateLimitProperties.Limit limit = resolveLimit(bucketName);
        validateLimit(bucketName, limit);
        validatePermits(permits);

        long used = getCurrentCount(bucketName, key);
        return used <= (long) limit.capacity() - permits;
    }

    public Usage getUsage(String bucketName, Long userId) {
        return getUsage(bucketName, "user:" + userId);
    }

    public Usage getUsage(String bucketName, String key) {
        RateLimitProperties.Limit limit = resolveLimit(bucketName);
        validateLimit(bucketName, limit);

        long used = getCurrentCount(bucketName, key);

        return new Usage(used, limit.capacity());
    }

    public long getCurrentCount(String bucketName, String key) {
        String rateLimitKey = buildKey(bucketName, key);

        try {
            String value = redisTemplate.opsForValue().get(rateLimitKey);
            onRedisSuccess();
            return value != null ? Long.parseLong(value) : 0L;
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
            LocalWindow window = localFallbackWindows.get(rateLimitKey);
            return window != null ? window.count() : 0L;
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
            case "login" -> rateLimitProperties.login();
            case "login-ip" -> rateLimitProperties.loginIp();
            case "signup" -> rateLimitProperties.signup();
            case "password-reset-request" -> rateLimitProperties.passwordResetRequest();
            case "password-reset-confirm" -> rateLimitProperties.passwordResetConfirm();
            case "password-change" -> rateLimitProperties.passwordChange();
            case "openai-monthly" -> rateLimitProperties.openaiMonthly();
            case "video-llm-monthly" -> rateLimitProperties.videoLlmMonthly();
            case "video-llm-daily" -> rateLimitProperties.videoLlmDaily();
            case "results-query" -> rateLimitProperties.resultsQuery();
            case "video-access-token" -> rateLimitProperties.videoAccessToken();
            case "job-status-poll" -> rateLimitProperties.jobStatusPoll();
            case "coach-daily" -> rateLimitProperties.coachDaily();
            default -> throw new IllegalArgumentException("Unknown rate limit bucket: " + bucketName);
        };
    }

    private void validateLimit(String bucketName, RateLimitProperties.Limit limit) {
        if (limit == null || limit.capacity() < 1 || limit.refillMinutes() < 1) {
            throw new IllegalStateException("Invalid rate limit configuration: " + bucketName);
        }
    }

    private void validatePermits(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1: " + permits);
        }
    }

    private boolean tryConsumeLocalFallback(
            String key,
            RateLimitProperties.Limit limit,
            int permits
    ) {
        Instant now = Instant.now();
        AtomicBoolean reserved = new AtomicBoolean(false);
        localFallbackWindows.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.expiresAt())) {
                if (permits > limit.capacity()) {
                    return new LocalWindow(0, now.plus(Duration.ofMinutes(limit.refillMinutes())));
                }
                reserved.set(true);
                return new LocalWindow(
                        permits,
                        now.plus(Duration.ofMinutes(limit.refillMinutes()))
                );
            }

            if ((long) current.count() + permits > limit.capacity()) {
                return current;
            }
            reserved.set(true);
            return new LocalWindow(current.count() + permits, current.expiresAt());
        });

        return reserved.get();
    }

    private boolean tryConsumeSingleLocalFallback(
            String key,
            RateLimitProperties.Limit limit
    ) {
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

    private String buildKey(String bucketName, String key) {
        return KEY_PREFIX + bucketName + ":" + key;
    }

    private record LocalWindow(
            int count,
            Instant expiresAt
    ) {
    }

    public record Usage(
            long used,
            long capacity
    ) {
        public long remaining() {
            return Math.max(0, capacity - used);
        }
    }
}
