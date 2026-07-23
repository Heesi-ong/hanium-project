package com.hanium.presentation.global.config;

import com.hanium.presentation.application.auth.RevokedAccessTokenWriter;
import com.hanium.presentation.domain.auth.repository.RevokedAccessTokenRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class JwtBlacklist {

    private static final Logger log = LoggerFactory.getLogger(JwtBlacklist.class);
    private static final String KEY_PREFIX = "jwt-blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final RevokedAccessTokenRepository revokedAccessTokenRepository;
    private final RevokedAccessTokenWriter revokedAccessTokenWriter;
    private final Counter redisReadFailureCounter;
    private final Counter redisWriteFailureCounter;
    private final Counter databaseReadFailureCounter;
    private final Counter databaseWriteFailureCounter;
    private final Counter redisHitCounter;
    private final Counter databaseHitCounter;
    private volatile boolean redisWarningLogged = false;

    public JwtBlacklist(
            StringRedisTemplate redisTemplate,
            RevokedAccessTokenRepository revokedAccessTokenRepository,
            RevokedAccessTokenWriter revokedAccessTokenWriter,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
        this.revokedAccessTokenWriter = revokedAccessTokenWriter;
        this.redisReadFailureCounter = counter(meterRegistry, "redis_read_failure");
        this.redisWriteFailureCounter = counter(meterRegistry, "redis_write_failure");
        this.databaseReadFailureCounter = counter(meterRegistry, "database_read_failure");
        this.databaseWriteFailureCounter = counter(meterRegistry, "database_write_failure");
        this.redisHitCounter = counter(meterRegistry, "redis_hit");
        this.databaseHitCounter = counter(meterRegistry, "database_hit");
    }

    public void blacklist(String token, Duration ttl) {
        if (token == null || token.isBlank() || ttl == null || ttl.compareTo(Duration.ZERO) <= 0) {
            return;
        }

        String tokenHash = sha256(token);
        Instant expiresAt = Instant.now().plus(ttl);
        persistRevocation(tokenHash, expiresAt);

        try {
            redisTemplate.opsForValue().set(buildKey(tokenHash), "true", ttl);
            onRedisSuccess();
        } catch (RuntimeException exception) {
            redisWriteFailureCounter.increment();
            onRedisFailure("write", exception);
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String tokenHash = sha256(token);
        try {
            Boolean exists = redisTemplate.hasKey(buildKey(tokenHash));
            onRedisSuccess();
            if (Boolean.TRUE.equals(exists)) {
                redisHitCounter.increment();
                return true;
            }
        } catch (RuntimeException exception) {
            redisReadFailureCounter.increment();
            onRedisFailure("read", exception);
        }

        try {
            boolean revoked = revokedAccessTokenRepository
                    .existsByTokenHashAndExpiresAtAfter(tokenHash, Instant.now());
            if (revoked) {
                databaseHitCounter.increment();
            }
            return revoked;
        } catch (DataAccessException exception) {
            databaseReadFailureCounter.increment();
            throw new JwtRevocationUnavailableException(
                    "JWT 폐기 상태를 확인할 수 없습니다.",
                    exception
            );
        }
    }

    private void persistRevocation(String tokenHash, Instant expiresAt) {
        try {
            revokedAccessTokenWriter.store(tokenHash, expiresAt);
        } catch (DataIntegrityViolationException exception) {
            // 같은 JWT로 동시에 로그아웃한 경우 한 transaction만 insert에 성공할 수 있다.
            // 이미 같은 hash가 존재하면 멱등 성공으로 처리하고, 그렇지 않으면 실제 DB
            // 무결성 문제이므로 가용성 오류로 승격한다.
            try {
                if (revokedAccessTokenRepository.existsById(tokenHash)) {
                    return;
                }
            } catch (DataAccessException lookupException) {
                exception.addSuppressed(lookupException);
            }
            databaseWriteFailureCounter.increment();
            throw new JwtRevocationUnavailableException(
                    "JWT 폐기 상태를 저장할 수 없습니다.",
                    exception
            );
        } catch (DataAccessException exception) {
            databaseWriteFailureCounter.increment();
            throw new JwtRevocationUnavailableException(
                    "JWT 폐기 상태를 저장할 수 없습니다.",
                    exception
            );
        }
    }

    private String buildKey(String tokenHash) {
        return KEY_PREFIX + tokenHash;
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

    private Counter counter(MeterRegistry meterRegistry, String result) {
        return Counter.builder("security.jwt.revocation")
                .description("JWT 폐기 원장/Redis 캐시 처리 결과")
                .tag("result", result)
                .register(meterRegistry);
    }

    private void onRedisFailure(String operation, RuntimeException exception) {
        if (!redisWarningLogged) {
            redisWarningLogged = true;
            log.warn(
                    "Redis JWT 폐기 캐시 사용 실패 - DB 최종 원장으로 계속 처리합니다. operation={}, 원인={}",
                    operation,
                    exception.toString()
            );
        }
    }

    private void onRedisSuccess() {
        if (redisWarningLogged) {
            redisWarningLogged = false;
            log.info("Redis JWT 폐기 캐시 연결이 복구되었습니다.");
        }
    }
}
