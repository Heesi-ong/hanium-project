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
import java.util.List;
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

    // Video LLM은 사용자 일간 1회와 전역 월간 NVIDIA 예상 호출 N회를 동시에 예약해야 한다.
    // 두 키를 개별 tryConsume으로 처리하면 daily 성공 직후 monthly가 경쟁 요청에 의해 거절될
    // 수 있고, 실제 provider 호출 없이 daily permit만 소모된다. 두 용량을 먼저 검사하고 둘 다
    // 허용될 때만 함께 INCRBY하는 Lua로 이 부분 예약을 제거한다.
    private static final RedisScript<Long> RESERVE_VIDEO_LLM_BUDGET_SCRIPT = new DefaultRedisScript<>(
            "local daily = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "local monthly = tonumber(redis.call('GET', KEYS[2]) or '0') "
                    + "local monthlyPermits = tonumber(ARGV[3]) "
                    + "local dailyCapacity = tonumber(ARGV[4]) "
                    + "local monthlyCapacity = tonumber(ARGV[5]) "
                    + "if daily + 1 > dailyCapacity then return 1 end "
                    + "if monthly + monthlyPermits > monthlyCapacity then return 2 end "
                    + "redis.call('INCRBY', KEYS[1], 1) "
                    + "redis.call('INCRBY', KEYS[2], monthlyPermits) "
                    + "if daily == 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "if monthly == 0 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end "
                    + "return 0",
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

    public VideoLlmBudgetReservation reserveVideoLlmBudget(
            Long ownerId,
            String monthKey,
            int monthlyPermits
    ) {
        if (monthKey == null || monthKey.isBlank()) {
            throw new IllegalArgumentException("monthKey must not be blank");
        }
        validatePermits(monthlyPermits);

        // owner가 없는 레거시 데이터는 기존 계약대로 일간 제한을 건너뛰되 월간 예산은
        // 반드시 예약한다. 정상 신규 작업은 ownerId가 항상 존재한다.
        if (ownerId == null) {
            return tryConsume("video-llm-monthly", monthKey, monthlyPermits)
                    ? VideoLlmBudgetReservation.RESERVED
                    : VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED;
        }

        RateLimitProperties.Limit dailyLimit = resolveLimit("video-llm-daily");
        RateLimitProperties.Limit monthlyLimit = resolveLimit("video-llm-monthly");
        validateLimit("video-llm-daily", dailyLimit);
        validateLimit("video-llm-monthly", monthlyLimit);

        String dailyKey = buildKey("video-llm-daily", "user:" + ownerId);
        String monthlyKey = buildKey("video-llm-monthly", monthKey);

        try {
            Long result = redisTemplate.execute(
                    RESERVE_VIDEO_LLM_BUDGET_SCRIPT,
                    List.of(dailyKey, monthlyKey),
                    String.valueOf(Duration.ofMinutes(dailyLimit.refillMinutes()).toSeconds()),
                    String.valueOf(Duration.ofMinutes(monthlyLimit.refillMinutes()).toSeconds()),
                    String.valueOf(monthlyPermits),
                    String.valueOf(dailyLimit.capacity()),
                    String.valueOf(monthlyLimit.capacity())
            );

            if (result == null) {
                throw new IllegalStateException("Redis Video LLM budget script returned null");
            }

            onRedisSuccess();
            return switch (result.intValue()) {
                case 0 -> VideoLlmBudgetReservation.RESERVED;
                case 1 -> VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED;
                case 2 -> VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED;
                default -> throw new IllegalStateException(
                        "Unexpected Redis Video LLM budget result: " + result
                );
            };
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
            return reserveVideoLlmBudgetLocalFallback(
                    dailyKey,
                    dailyLimit,
                    monthlyKey,
                    monthlyLimit,
                    monthlyPermits
            );
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

    // 관리 화면의 사전 안내처럼 소비 없이 현재 허용 여부만 확인할 때 사용한다.
    // 여러 예산을 함께 예약해야 하는 실행 경로는 peek 뒤 순차 소비하지 말고 위의
    // reserveVideoLlmBudget처럼 단일 원자 연산을 사용해야 한다.
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

    private VideoLlmBudgetReservation reserveVideoLlmBudgetLocalFallback(
            String dailyKey,
            RateLimitProperties.Limit dailyLimit,
            String monthlyKey,
            RateLimitProperties.Limit monthlyLimit,
            int monthlyPermits
    ) {
        Instant now = Instant.now();

        // ConcurrentHashMap의 키별 compute 두 번으로는 두 키 전체의 원자성을 보장할 수 없다.
        // Redis 장애 fallback은 이 backend 인스턴스 범위이므로 map 자체를 짧게 잠가 판정과
        // 두 창 갱신 사이에 다른 worker가 끼어들지 못하게 한다.
        synchronized (localFallbackWindows) {
            LocalWindow dailyWindow = activeWindow(localFallbackWindows.get(dailyKey), now);
            LocalWindow monthlyWindow = activeWindow(localFallbackWindows.get(monthlyKey), now);
            int dailyCount = dailyWindow == null ? 0 : dailyWindow.count();
            int monthlyCount = monthlyWindow == null ? 0 : monthlyWindow.count();

            if ((long) dailyCount + 1 > dailyLimit.capacity()) {
                return VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED;
            }
            if ((long) monthlyCount + monthlyPermits > monthlyLimit.capacity()) {
                return VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED;
            }

            localFallbackWindows.put(
                    dailyKey,
                    new LocalWindow(
                            dailyCount + 1,
                            dailyWindow == null
                                    ? now.plus(Duration.ofMinutes(dailyLimit.refillMinutes()))
                                    : dailyWindow.expiresAt()
                    )
            );
            localFallbackWindows.put(
                    monthlyKey,
                    new LocalWindow(
                            monthlyCount + monthlyPermits,
                            monthlyWindow == null
                                    ? now.plus(Duration.ofMinutes(monthlyLimit.refillMinutes()))
                                    : monthlyWindow.expiresAt()
                    )
            );
            return VideoLlmBudgetReservation.RESERVED;
        }
    }

    private LocalWindow activeWindow(LocalWindow window, Instant now) {
        return window != null && now.isBefore(window.expiresAt()) ? window : null;
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

    public enum VideoLlmBudgetReservation {
        RESERVED,
        DAILY_LIMIT_EXCEEDED,
        MONTHLY_LIMIT_EXCEEDED
    }
}
