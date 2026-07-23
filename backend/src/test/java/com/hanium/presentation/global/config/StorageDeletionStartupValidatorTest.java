package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StorageDeletionStartupValidatorTest {

    @Test
    void acceptsConsistentSettings() {
        StorageDeletionStartupValidator validator = validator(8, 2, 240, 1, 5, 30, 10);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveSettings() {
        StorageDeletionStartupValidator validator = validator(0, 2, 240, 1, 5, 30, 10);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("storage.deletion.max-attempts");
    }

    @Test
    void rejectsMaxBackoffSmallerThanBaseBackoff() {
        StorageDeletionStartupValidator validator = validator(8, 10, 5, 1, 5, 30, 10);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("max-backoff-minutes");
    }

    @Test
    void rejectsClaimLeaseNotLongerThanWorkerLock() {
        StorageDeletionStartupValidator validator = validator(8, 2, 240, 5, 5, 30, 10);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("claim-lease-minutes");
    }

    private StorageDeletionStartupValidator validator(
            int maxAttempts,
            long baseBackoffMinutes,
            long maxBackoffMinutes,
            long workerLockTtlMinutes,
            long claimLeaseMinutes,
            int completedRetentionDays,
            long retentionLockTtlMinutes
    ) {
        return new StorageDeletionStartupValidator(
                maxAttempts,
                baseBackoffMinutes,
                maxBackoffMinutes,
                workerLockTtlMinutes,
                claimLeaseMinutes,
                completedRetentionDays,
                retentionLockTtlMinutes
        );
    }
}
