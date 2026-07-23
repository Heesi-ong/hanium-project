package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.auth.entity.RevokedAccessToken;
import com.hanium.presentation.domain.auth.repository.RevokedAccessTokenRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevokedAccessTokenCleanupServiceTest {

    private final RevokedAccessTokenRepository repository = mock(RevokedAccessTokenRepository.class);
    private final SchedulerDistributedLock distributedLock = mock(SchedulerDistributedLock.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    @Test
    void deletesExpiredRowsInBoundedBatch() {
        when(distributedLock.tryLock(
                eq("jwt-revocation-cleanup"),
                eq(Duration.ofMinutes(10))
        )).thenReturn(true);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        List<RevokedAccessToken> expired = List.of(
                RevokedAccessToken.create("a".repeat(64), Instant.now().minusSeconds(60)),
                RevokedAccessToken.create("b".repeat(64), Instant.now().minusSeconds(30))
        );
        when(repository.findTop500ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class)))
                .thenReturn(expired);
        RevokedAccessTokenCleanupService service = new RevokedAccessTokenCleanupService(
                repository,
                distributedLock,
                transactionManager,
                10
        );

        service.cleanupExpiredTokens();

        verify(repository).deleteAllInBatch(expired);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void skipsCleanupWhenAnotherInstanceOwnsLock() {
        when(distributedLock.tryLock(any(), any())).thenReturn(false);
        RevokedAccessTokenCleanupService service = new RevokedAccessTokenCleanupService(
                repository,
                distributedLock,
                transactionManager,
                10
        );

        service.cleanupExpiredTokens();

        verify(repository, never())
                .findTop500ByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class));
    }
}
