package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetEmailOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailOutboxWorker.class);

    private final PasswordResetEmailTaskRepository taskRepository;
    private final PasswordResetOutboxCrypto outboxCrypto;
    private final PasswordResetEmailSender emailSender;
    private final SchedulerDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final Duration claimLease;
    private final Duration lockTtl;

    public PasswordResetEmailOutboxWorker(
            PasswordResetEmailTaskRepository taskRepository,
            PasswordResetOutboxCrypto outboxCrypto,
            PasswordResetEmailSender emailSender,
            SchedulerDistributedLock distributedLock,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry,
            @Value("${password-reset.outbox.max-attempts:5}") int maxAttempts,
            @Value("${password-reset.outbox.base-backoff-seconds:30}") long baseBackoffSeconds,
            @Value("${password-reset.outbox.max-backoff-seconds:300}") long maxBackoffSeconds,
            @Value("${password-reset.outbox.claim-lease-seconds:60}") long claimLeaseSeconds,
            // poll-interval-ms 기본값(5초)보다 짧아야 합니다. tryLock에는 명시적 unlock이 없어
            // TTL이 다 돼야 풀리므로, TTL이 실행 간격보다 길면 같은(유일한) 인스턴스가 자기 자신의
            // 락에 걸려 대부분의 스케줄을 건너뜁니다(2026-07-23 코드 리뷰 후속 발견).
            @Value("${scheduler.lock.password-reset-email-worker-ttl-seconds:4}") long lockTtlSeconds
    ) {
        this.taskRepository = taskRepository;
        this.outboxCrypto = outboxCrypto;
        this.emailSender = emailSender;
        this.distributedLock = distributedLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);

        meterRegistry.gauge(
                "password.reset.email.pending",
                taskRepository,
                repository -> (double) repository.countByStatus(PasswordResetEmailTaskStatus.PENDING)
        );
        meterRegistry.gauge(
                "password.reset.email.dead_letter",
                taskRepository,
                repository -> (double) repository.countByStatus(PasswordResetEmailTaskStatus.DEAD_LETTER)
        );
    }

    @Scheduled(fixedDelayString = "${password-reset.outbox.poll-interval-ms:5000}")
    public void processPendingEmails() {
        if (!distributedLock.tryLock("password-reset-email-worker", lockTtl)) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            int cancelled = taskRepository.cancelExpiredDeadLetters(
                    PasswordResetEmailTaskStatus.DEAD_LETTER,
                    PasswordResetEmailTaskStatus.CANCELLED,
                    LocalDateTime.now(),
                    "TOKEN_EXPIRED_IN_DEAD_LETTER"
            );
            if (cancelled > 0) {
                log.info("만료된 비밀번호 재설정 이메일 DEAD_LETTER를 취소했습니다. cancelled={}", cancelled);
            }
        });

        List<PasswordResetEmailTask> dueTasks =
                taskRepository.findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
                        PasswordResetEmailTaskStatus.PENDING,
                        LocalDateTime.now()
                );
        for (PasswordResetEmailTask task : dueTasks) {
            processOne(task.getId());
        }
    }

    private void processOne(Long taskId) {
        ClaimedEmail claimedEmail = claim(taskId);
        if (claimedEmail == null) {
            return;
        }

        try {
            String resetLink = outboxCrypto.decrypt(
                    claimedEmail.encryptedResetLink(),
                    claimedEmail.tokenHash()
            );
            emailSender.sendPasswordResetLink(
                    claimedEmail.recipientEmail(),
                    claimedEmail.userId(),
                    resetLink
            );
            complete(claimedEmail);
        } catch (Exception exception) {
            fail(claimedEmail, exception);
        }
    }

    private ClaimedEmail claim(Long taskId) {
        return transactionTemplate.execute(status -> {
            PasswordResetEmailTask task = taskRepository.findByIdForUpdate(taskId).orElse(null);
            LocalDateTime now = LocalDateTime.now();
            if (task == null
                    || task.getStatus() != PasswordResetEmailTaskStatus.PENDING
                    || task.getNextAttemptAt().isAfter(now)) {
                return null;
            }

            if (!task.getPasswordResetToken().isUsable(now)) {
                task.cancel("TOKEN_EXPIRED_OR_INVALIDATED");
                return null;
            }

            String token = UUID.randomUUID().toString();
            task.claim(token, now.plus(claimLease));
            return new ClaimedEmail(
                    task.getId(),
                    task.getUserId(),
                    task.getRecipientEmail(),
                    task.getEncryptedResetLink(),
                    task.getPasswordResetToken().getTokenHash(),
                    token
            );
        });
    }

    private void complete(ClaimedEmail claimedEmail) {
        transactionTemplate.executeWithoutResult(status ->
                taskRepository.findByIdForUpdate(claimedEmail.id())
                        .filter(task -> task.isClaimedBy(claimedEmail.processingToken()))
                        .ifPresent(PasswordResetEmailTask::markCompleted)
        );
    }

    private void fail(ClaimedEmail claimedEmail, Exception exception) {
        transactionTemplate.executeWithoutResult(status ->
                taskRepository.findByIdForUpdate(claimedEmail.id())
                        .filter(task -> task.isClaimedBy(claimedEmail.processingToken()))
                        .ifPresent(task -> {
                            task.markFailedAndScheduleRetry(
                                    exception.toString(),
                                    maxAttempts,
                                    baseBackoffSeconds,
                                    maxBackoffSeconds
                            );
                            if (task.getStatus() == PasswordResetEmailTaskStatus.DEAD_LETTER) {
                                log.error(
                                        "PASSWORD_RESET_EMAIL_DEAD_LETTER taskId={} userId={} attempts={} reason={}",
                                        task.getId(), task.getUserId(), task.getAttemptCount(), exception.toString()
                                );
                            } else {
                                log.warn(
                                        "PASSWORD_RESET_EMAIL_RETRY_SCHEDULED taskId={} userId={} attempt={} reason={}",
                                        task.getId(), task.getUserId(), task.getAttemptCount(), exception.toString()
                                );
                            }
                        })
        );
    }

    private record ClaimedEmail(
            Long id,
            Long userId,
            String recipientEmail,
            String encryptedResetLink,
            String tokenHash,
            String processingToken
    ) {
    }
}
