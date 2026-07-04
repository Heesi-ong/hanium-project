package com.hanium.presentation.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class JwtBlacklist {

    private static final Logger log = LoggerFactory.getLogger(JwtBlacklist.class);
    private static final String KEY_PREFIX = "jwt-blacklist:";

    private final StringRedisTemplate redisTemplate;
    private volatile boolean redisWarningLogged = false;

    public JwtBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void blacklist(String token, Duration ttl) {
        if (token == null || token.isBlank() || ttl == null || ttl.compareTo(Duration.ZERO) <= 0) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(buildKey(token), "true", ttl);
            onRedisSuccess();
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        try {
            Boolean exists = redisTemplate.hasKey(buildKey(token));
            onRedisSuccess();
            return Boolean.TRUE.equals(exists);
        } catch (RuntimeException exception) {
            onRedisFailure(exception);
            return false;
        }
    }

    private String buildKey(String token) {
        return KEY_PREFIX + sha256(token);
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private void onRedisFailure(RuntimeException exception) {
        if (!redisWarningLogged) {
            redisWarningLogged = true;
            log.warn(
                    "Redis JWT 블랙리스트 사용 실패 - 토큰 블랙리스트 검사를 건너뜁니다. 원인: {}",
                    exception.toString()
            );
        }
    }

    private void onRedisSuccess() {
        if (redisWarningLogged) {
            redisWarningLogged = false;
            log.info("Redis JWT 블랙리스트 연결이 복구되었습니다.");
        }
    }
}
