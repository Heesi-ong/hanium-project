package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.auth.repository.RevokedAccessTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(RevokedAccessTokenWriter.class)
class RevokedAccessTokenWriterIntegrationTest {

    @Autowired
    private RevokedAccessTokenRepository repository;

    @Autowired
    private RevokedAccessTokenWriter writer;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void storesOnlyHashAndTreatsRepeatedRevocationAsIdempotentUpdate() {
        String tokenHash = "a".repeat(64);
        Instant expiresAt = Instant.now().plusSeconds(1800);

        writer.store(tokenHash, expiresAt);
        writer.store(tokenHash, expiresAt);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.existsByTokenHashAndExpiresAtAfter(tokenHash, Instant.now()))
                .isTrue();
    }

    @Test
    void expiredDatabaseRecordNoLongerRevokesTokenBeforeCleanupRuns() {
        String tokenHash = "b".repeat(64);
        writer.store(tokenHash, Instant.now().minusSeconds(1));

        assertThat(repository.existsByTokenHashAndExpiresAtAfter(tokenHash, Instant.now()))
                .isFalse();
    }
}
