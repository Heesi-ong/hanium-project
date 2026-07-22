package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

// 비밀번호 재설정 토큰 정리도 실행/백그라운드 담당 인스턴스(monolith/worker)에서만 돌립니다.
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class PasswordResetTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanupService.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final long retentionDays;
    private final Duration lockTtl;

    public PasswordResetTokenCleanupService(
            PasswordResetTokenRepository passwordResetTokenRepository,
            SchedulerDistributedLock schedulerDistributedLock,
            @Value("${password-reset.cleanup.retention-days:7}") long retentionDays,
            @Value("${scheduler.lock.password-reset-cleanup-ttl-minutes:10}") long lockTtlMinutes
    ) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.retentionDays = retentionDays;
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
    }

    @Scheduled(cron = "${password-reset.cleanup.cron:0 30 3 * * *}")
    @Transactional
    public void cleanupExpiredTokens() {
        if (!schedulerDistributedLock.tryLock("password-reset-token-cleanup", lockTtl)) {
            log.info("비밀번호 재설정 토큰 정리 실행을 건너뜁니다. 다른 backend 인스턴스가 락을 보유 중입니다.");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deletedTokens = passwordResetTokenRepository.deleteByExpiresAtBefore(cutoff);

        log.info(
                "비밀번호 재설정 토큰 정리 완료. cutoff={}, deletedTokens={}",
                cutoff,
                deletedTokens
        );
    }
}
