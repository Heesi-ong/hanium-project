package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 장애 시 JWT 폐기 DB fallback과 스케줄러 fail-open 정책이 있어도, Lettuce 명령이
 * 오래 대기하면 HTTP 요청과 scheduler thread가 먼저 고갈된다. 연결·명령 timeout이
 * 양수인지 기동 단계에서 검증해 "fallback 코드는 있지만 60초 동안 진입하지 못하는"
 * 잘못된 운영 설정을 차단한다.
 */
@Component
public class RedisTimeoutStartupValidator {

    private final Duration connectTimeout;
    private final Duration commandTimeout;

    public RedisTimeoutStartupValidator(
            @Value("${spring.data.redis.connect-timeout:2s}") Duration connectTimeout,
            @Value("${spring.data.redis.timeout:2s}") Duration commandTimeout
    ) {
        this.connectTimeout = connectTimeout;
        this.commandTimeout = commandTimeout;
    }

    @PostConstruct
    public void validate() {
        requirePositive("spring.data.redis.connect-timeout", connectTimeout);
        requirePositive("spring.data.redis.timeout", commandTimeout);
    }

    private void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + "는 0보다 커야 합니다. value=" + value);
        }
    }
}
