package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PasswordResetOutboxStartupValidatorTest {

    @Test
    void acceptsConsistentSettings() {
        PasswordResetOutboxStartupValidator validator = validator(5000, 5, 30, 300, 60, 4, 30);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveSettings() {
        PasswordResetOutboxStartupValidator validator = validator(0, 5, 30, 300, 60, 4, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("poll-interval-ms");
    }

    @Test
    void rejectsLeaseNotLongerThanDistributedLock() {
        PasswordResetOutboxStartupValidator validator = validator(5000, 5, 30, 300, 4, 4, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("claim-lease-seconds");
    }

    @Test
    void rejectsBackoffAtOrBeyondTokenLifetime() {
        PasswordResetOutboxStartupValidator validator = validator(5000, 5, 30, 1800, 60, 4, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("토큰 만료");
    }

    // tryLock에는 명시적 unlock이 없어 TTL이 다 돼야 풀린다. TTL이 poll 주기보다 길거나 같으면
    // 같은(유일한) 인스턴스가 스스로 쥔 락에 막혀 대부분의 스케줄을 건너뛴다
    // (2026-07-23 코드 리뷰 후속 발견 - StorageDeletionOutboxWorker와 동일한 버그 클래스).
    @Test
    void rejectsWorkerLockTtlNotShorterThanPollInterval() {
        PasswordResetOutboxStartupValidator validator = validator(5000, 5, 30, 300, 60, 5, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("poll-interval-ms");
    }

    private PasswordResetOutboxStartupValidator validator(
            long pollIntervalMs,
            int maxAttempts,
            long baseBackoffSeconds,
            long maxBackoffSeconds,
            long claimLeaseSeconds,
            long lockTtlSeconds,
            long tokenExpirationMinutes
    ) {
        return new PasswordResetOutboxStartupValidator(
                pollIntervalMs,
                maxAttempts,
                baseBackoffSeconds,
                maxBackoffSeconds,
                claimLeaseSeconds,
                lockTtlSeconds,
                tokenExpirationMinutes
        );
    }
}
