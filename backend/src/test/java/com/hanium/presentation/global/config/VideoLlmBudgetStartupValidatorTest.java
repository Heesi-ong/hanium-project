package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class VideoLlmBudgetStartupValidatorTest {

    @Test
    void acceptsPositiveFiniteBudgetSettings() {
        VideoLlmBudgetStartupValidator validator =
                new VideoLlmBudgetStartupValidator(100, 30);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonFiniteChunkDuration() {
        VideoLlmBudgetStartupValidator validator =
                new VideoLlmBudgetStartupValidator(Double.NaN, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("chunk-duration-seconds");
    }

    @Test
    void rejectsNonPositiveChunkDuration() {
        VideoLlmBudgetStartupValidator validator =
                new VideoLlmBudgetStartupValidator(0, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("chunk-duration-seconds");
    }

    @Test
    void rejectsNonPositiveMaximumDuration() {
        VideoLlmBudgetStartupValidator validator =
                new VideoLlmBudgetStartupValidator(100, 0);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("video.max-duration-minutes");
    }
}
