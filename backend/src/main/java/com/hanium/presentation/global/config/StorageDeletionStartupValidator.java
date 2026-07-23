package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StorageDeletionStartupValidator {

    private final int maxAttempts;
    private final long baseBackoffMinutes;
    private final long maxBackoffMinutes;
    private final long workerLockTtlMinutes;
    private final long claimLeaseMinutes;
    private final int completedRetentionDays;
    private final long retentionLockTtlMinutes;

    public StorageDeletionStartupValidator(
            @Value("${storage.deletion.max-attempts:8}") int maxAttempts,
            @Value("${storage.deletion.base-backoff-minutes:2}") long baseBackoffMinutes,
            @Value("${storage.deletion.max-backoff-minutes:240}") long maxBackoffMinutes,
            @Value("${scheduler.lock.storage-deletion-worker-ttl-minutes:1}") long workerLockTtlMinutes,
            @Value("${storage.deletion.claim-lease-minutes:5}") long claimLeaseMinutes,
            @Value("${storage.deletion.completed-retention-days:30}") int completedRetentionDays,
            @Value("${scheduler.lock.storage-deletion-retention-ttl-minutes:10}") long retentionLockTtlMinutes
    ) {
        this.maxAttempts = maxAttempts;
        this.baseBackoffMinutes = baseBackoffMinutes;
        this.maxBackoffMinutes = maxBackoffMinutes;
        this.workerLockTtlMinutes = workerLockTtlMinutes;
        this.claimLeaseMinutes = claimLeaseMinutes;
        this.completedRetentionDays = completedRetentionDays;
        this.retentionLockTtlMinutes = retentionLockTtlMinutes;
    }

    @PostConstruct
    public void validate() {
        requirePositive("storage.deletion.max-attempts", maxAttempts);
        requirePositive("storage.deletion.base-backoff-minutes", baseBackoffMinutes);
        requirePositive("storage.deletion.max-backoff-minutes", maxBackoffMinutes);
        requirePositive("scheduler.lock.storage-deletion-worker-ttl-minutes", workerLockTtlMinutes);
        requirePositive("storage.deletion.claim-lease-minutes", claimLeaseMinutes);
        requirePositive("storage.deletion.completed-retention-days", completedRetentionDays);
        requirePositive("scheduler.lock.storage-deletion-retention-ttl-minutes", retentionLockTtlMinutes);

        if (maxBackoffMinutes < baseBackoffMinutes) {
            throw new IllegalStateException(
                    "storage.deletion.max-backoff-minutes는 base-backoff-minutes 이상이어야 합니다."
            );
        }
        if (claimLeaseMinutes <= workerLockTtlMinutes) {
            throw new IllegalStateException(
                    "storage.deletion.claim-lease-minutes는 "
                            + "scheduler.lock.storage-deletion-worker-ttl-minutes보다 커야 합니다."
            );
        }
    }

    private void requirePositive(String name, long value) {
        if (value < 1) {
            throw new IllegalStateException(name + "(" + value + ")는 1 이상이어야 합니다.");
        }
    }
}
