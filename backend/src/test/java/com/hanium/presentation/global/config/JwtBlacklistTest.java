package com.hanium.presentation.global.config;

import com.hanium.presentation.application.auth.RevokedAccessTokenWriter;
import com.hanium.presentation.domain.auth.repository.RevokedAccessTokenRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtBlacklistTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final RevokedAccessTokenRepository repository = mock(RevokedAccessTokenRepository.class);
    private final RevokedAccessTokenWriter writer = mock(RevokedAccessTokenWriter.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final JwtBlacklist jwtBlacklist = new JwtBlacklist(
            redisTemplate,
            repository,
            writer,
            meterRegistry
    );

    @Test
    void blacklistPersistsDatabaseRecordBeforeWritingRedisCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.ArgumentCaptor<String> tokenHashCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);

        jwtBlacklist.blacklist("access-token", Duration.ofMinutes(30));

        verify(writer).store(tokenHashCaptor.capture(), any(Instant.class));
        verify(valueOperations).set(anyString(), eq("true"), eq(Duration.ofMinutes(30)));
        assertThat(tokenHashCaptor.getValue())
                .hasSize(64)
                .doesNotContain("access-token");
        verify(redisTemplate, atLeastOnce()).opsForValue();
    }

    @Test
    void blacklistStillSucceedsWhenRedisWriteFailsBecauseDatabaseIsAuthoritative() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        jwtBlacklist.blacklist("access-token", Duration.ofMinutes(30));

        verify(writer).store(anyString(), any(Instant.class));
        assertThat(counter("redis_write_failure")).isEqualTo(1.0);
    }

    @Test
    void blacklistFailsClosedWhenDatabaseWriteFails() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(writer)
                .store(anyString(), any(Instant.class));

        assertThatThrownBy(() -> jwtBlacklist.blacklist(
                "access-token",
                Duration.ofMinutes(30)
        ))
                .isInstanceOf(JwtRevocationUnavailableException.class)
                .hasMessageContaining("저장할 수 없습니다");

        verify(redisTemplate, never()).opsForValue();
        assertThat(counter("database_write_failure")).isEqualTo(1.0);
    }

    @Test
    void isBlacklistedReturnsTrueImmediatelyForRedisPositiveCacheHit() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isTrue();
        verify(repository, never()).existsByTokenHashAndExpiresAtAfter(anyString(), any());
        assertThat(counter("redis_hit")).isEqualTo(1.0);
    }

    @Test
    void isBlacklistedChecksDatabaseWhenRedisDoesNotContainToken() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(repository.existsByTokenHashAndExpiresAtAfter(anyString(), any(Instant.class)))
                .thenReturn(true);

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isTrue();
        assertThat(counter("database_hit")).isEqualTo(1.0);
    }

    @Test
    void isBlacklistedReturnsFalseOnlyWhenRedisAndDatabaseBothConfirmNotRevoked() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(repository.existsByTokenHashAndExpiresAtAfter(anyString(), any(Instant.class)))
                .thenReturn(false);

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isFalse();
    }

    @Test
    void isBlacklistedFallsBackToDatabaseWhenRedisReadFails() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(repository.existsByTokenHashAndExpiresAtAfter(anyString(), any(Instant.class)))
                .thenReturn(true);

        boolean blacklisted = jwtBlacklist.isBlacklisted("access-token");

        assertThat(blacklisted).isTrue();
        assertThat(counter("redis_read_failure")).isEqualTo(1.0);
        assertThat(counter("database_hit")).isEqualTo(1.0);
    }

    @Test
    void isBlacklistedFailsClosedWhenDatabaseFallbackAlsoFails() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(repository.existsByTokenHashAndExpiresAtAfter(anyString(), any(Instant.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> jwtBlacklist.isBlacklisted("access-token"))
                .isInstanceOf(JwtRevocationUnavailableException.class)
                .hasMessageContaining("확인할 수 없습니다");

        assertThat(counter("redis_read_failure")).isEqualTo(1.0);
        assertThat(counter("database_read_failure")).isEqualTo(1.0);
    }

    @Test
    void emptyTokenIsNeverPersistedOrQueried() {
        jwtBlacklist.blacklist(" ", Duration.ofMinutes(30));

        assertThat(jwtBlacklist.isBlacklisted(" ")).isFalse();
        verify(writer, never()).store(anyString(), any(Instant.class));
        verify(redisTemplate, never()).hasKey(anyString());
    }

    private double counter(String result) {
        return meterRegistry.get("security.jwt.revocation")
                .tag("result", result)
                .counter()
                .count();
    }
}
