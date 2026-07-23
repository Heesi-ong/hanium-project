package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.auth.entity.RevokedAccessToken;
import com.hanium.presentation.domain.auth.repository.RevokedAccessTokenRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class RevokedAccessTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessTokenCleanupService.class);

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final TransactionTemplate transactionTemplate;
    private final Duration lockTtl;

    public RevokedAccessTokenCleanupService(
            RevokedAccessTokenRepository revokedAccessTokenRepository,
            SchedulerDistributedLock schedulerDistributedLock,
            PlatformTransactionManager transactionManager,
            @Value("${scheduler.lock.jwt-revocation-cleanup-ttl-minutes:10}") long lockTtlMinutes
    ) {
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
    }

    @Scheduled(cron = "${security.jwt.revocation.cleanup-cron:0 15 * * * *}")
    public void cleanupExpiredTokens() {
        if (!schedulerDistributedLock.tryLock("jwt-revocation-cleanup", lockTtl)) {
            return;
        }

        Instant cutoff = Instant.now();
        int deleted = 0;
        int deletedBatch;
        do {
            deletedBatch = transactionTemplate.execute(status -> {
                List<RevokedAccessToken> expiredTokens = revokedAccessTokenRepository
                        .findTop500ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff);
                revokedAccessTokenRepository.deleteAllInBatch(expiredTokens);
                return expiredTokens.size();
            });
            deleted += deletedBatch;
        } while (deletedBatch == 500);

        if (deleted > 0) {
            log.info("만료된 JWT 폐기 원장 행을 정리했습니다. deleted={}, cutoff={}", deleted, cutoff);
        }
    }
}
