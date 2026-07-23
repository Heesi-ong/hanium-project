package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AnalysisTimeoutStartupValidatorTest {

    @Test
    void doesNotThrowWhenJobTimeoutIsSmallerThanStuckJobThreshold() {
        AnalysisTimeoutStartupValidator validator = new AnalysisTimeoutStartupValidator(20, 30);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void throwsWhenJobTimeoutEqualsStuckJobThreshold() {
        AnalysisTimeoutStartupValidator validator = new AnalysisTimeoutStartupValidator(30, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("analysis.job.timeout-minutes");
    }

    @Test
    void throwsWhenJobTimeoutExceedsStuckJobThreshold() {
        AnalysisTimeoutStartupValidator validator = new AnalysisTimeoutStartupValidator(40, 30);

        assertThatIllegalStateException().isThrownBy(validator::validate);
    }

    @Test
    void throwsWhenJobTimeoutIsNotPositive() {
        AnalysisTimeoutStartupValidator validator = new AnalysisTimeoutStartupValidator(0, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("analysis.job.timeout-minutes")
                .withMessageContaining("1 이상");
    }

    @Test
    void throwsWhenStuckJobThresholdIsNotPositive() {
        AnalysisTimeoutStartupValidator validator = new AnalysisTimeoutStartupValidator(1, 0);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("analysis.stuck-job.max-running-minutes")
                .withMessageContaining("1 이상");
    }
}
