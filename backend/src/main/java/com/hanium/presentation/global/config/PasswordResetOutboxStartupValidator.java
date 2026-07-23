package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetOutboxStartupValidator {

    private final long pollIntervalMs;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final long claimLeaseSeconds;
    private final long workerLockTtlSeconds;
    private final long tokenExpirationMinutes;

    public PasswordResetOutboxStartupValidator(
            @Value("${password-reset.outbox.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${password-reset.outbox.max-attempts:5}") int maxAttempts,
            @Value("${password-reset.outbox.base-backoff-seconds:30}") long baseBackoffSeconds,
            @Value("${password-reset.outbox.max-backoff-seconds:300}") long maxBackoffSeconds,
            @Value("${password-reset.outbox.claim-lease-seconds:60}") long claimLeaseSeconds,
            @Value("${scheduler.lock.password-reset-email-worker-ttl-seconds:30}") long workerLockTtlSeconds,
            @Value("${password-reset.token-expiration-minutes:30}") long tokenExpirationMinutes
    ) {
        this.pollIntervalMs = pollIntervalMs;
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.claimLeaseSeconds = claimLeaseSeconds;
        this.workerLockTtlSeconds = workerLockTtlSeconds;
        this.tokenExpirationMinutes = tokenExpirationMinutes;
    }

    @PostConstruct
    public void validate() {
        requirePositive("password-reset.outbox.poll-interval-ms", pollIntervalMs);
        requirePositive("password-reset.outbox.max-attempts", maxAttempts);
        requirePositive("password-reset.outbox.base-backoff-seconds", baseBackoffSeconds);
        requirePositive("password-reset.outbox.max-backoff-seconds", maxBackoffSeconds);
        requirePositive("password-reset.outbox.claim-lease-seconds", claimLeaseSeconds);
        requirePositive("scheduler.lock.password-reset-email-worker-ttl-seconds", workerLockTtlSeconds);
        requirePositive("password-reset.token-expiration-minutes", tokenExpirationMinutes);

        if (maxBackoffSeconds < baseBackoffSeconds) {
            throw new IllegalStateException(
                    "password-reset.outbox.max-backoff-seconds는 base-backoff-seconds 이상이어야 합니다."
            );
        }
        if (claimLeaseSeconds <= workerLockTtlSeconds) {
            throw new IllegalStateException(
                    "password-reset.outbox.claim-lease-seconds는 "
                            + "scheduler.lock.password-reset-email-worker-ttl-seconds보다 커야 합니다."
            );
        }
        // tryLock에는 명시적 unlock이 없어 TTL이 다 돼야 풀린다. TTL이 poll 주기보다 길면
        // 같은(유일한) 인스턴스가 스스로 쥔 락에 막혀 대부분의 스케줄을 건너뛴다
        // (StorageDeletionOutboxWorker/QueuedAnalysisJobResumeService에서 이미 겪은 버그와 동일).
        if (workerLockTtlSeconds * 1000 >= pollIntervalMs) {
            throw new IllegalStateException(
                    "scheduler.lock.password-reset-email-worker-ttl-seconds는 "
                            + "password-reset.outbox.poll-interval-ms보다 짧아야 합니다."
            );
        }
        if (maxBackoffSeconds >= tokenExpirationMinutes * 60) {
            throw new IllegalStateException(
                    "password-reset.outbox.max-backoff-seconds는 토큰 만료 시간보다 짧아야 합니다."
            );
        }
    }

    private void requirePositive(String name, long value) {
        if (value < 1) {
            throw new IllegalStateException(name + "(" + value + ")는 1 이상이어야 합니다.");
        }
    }
}
