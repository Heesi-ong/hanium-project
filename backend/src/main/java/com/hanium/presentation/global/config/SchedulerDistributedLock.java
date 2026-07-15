package com.hanium.presentation.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 여러 backend 인스턴스가 같은 스케줄러(정리 작업, 워치도그 등)를 동시에 실행하지 않도록
 * Redis 기반 분산 락을 제공합니다.
 *
 * <p><b>Fail-open 정책</b>: Redis 호출 자체가 실패하면(연결 끊김, 타임아웃 등) 락 없이
 * 스케줄러를 그냥 실행시킵니다({@link #tryLock}이 {@code true}를 반환). 이는 의도된 선택입니다 -
 * Redis 장애 시 정리 작업/워치도그가 아예 멈추는 것보다는, 인스턴스가 1대뿐인 대부분의 운영
 * 환경에서는 중복 실행 위험을 감수하고 계속 도는 편이 안전하다고 판단했습니다.
 * 인스턴스를 2대 이상으로 늘려 운영할 경우, Redis 장애 시 스케줄러가 중복 실행될 수 있다는
 * 점을 감안해야 합니다. 자세한 내용은 {@code docs/ops/scheduler-distributed-lock.md} 참고.</p>
 */
@Component
public class SchedulerDistributedLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerDistributedLock.class);
    private static final String KEY_PREFIX = "scheduler-lock:";

    private final StringRedisTemplate redisTemplate;

    private volatile boolean redisWarningLogged = false;

    public SchedulerDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryLock(String lockName, Duration ttl) {
        String key = KEY_PREFIX + lockName;
        String value = UUID.randomUUID().toString();

        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
            onRedisSuccess();
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException exception) {
            onRedisFailure(lockName, exception);
            return true;
        }
    }

    private void onRedisFailure(String lockName, RuntimeException exception) {
        if (!redisWarningLogged) {
            redisWarningLogged = true;
            log.warn(
                    "Redis 스케줄러 분산 락 사용 실패 - 락 없이 스케줄러를 실행합니다. lockName={}, 원인={}",
                    lockName,
                    exception.toString()
            );
        }
    }

    private void onRedisSuccess() {
        if (redisWarningLogged) {
            redisWarningLogged = false;
            log.info("Redis 스케줄러 분산 락 연결이 복구되었습니다.");
        }
    }
}
