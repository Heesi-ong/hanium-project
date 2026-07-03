package com.hanium.presentation.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

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
