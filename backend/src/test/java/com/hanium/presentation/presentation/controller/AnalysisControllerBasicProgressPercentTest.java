package com.hanium.presentation.presentation.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisControllerBasicProgressPercentTest {

    @ParameterizedTest
    @CsvSource({
            "1, 9, 10",
            "2, 9, 14",
            "5, 9, 24",
            "8, 9, 35",
            "9, 9, 38",
    })
    void mapsBasicStepsAcrossTheAdvertisedTenToThirtyEightRange(
            int stepNo,
            int totalSteps,
            int expectedPercent
    ) {
        assertThat(AnalysisController.basicAnalysisPercent(stepNo, totalSteps))
                .isEqualTo(expectedPercent);
    }

    @Test
    void lastStepReachesThirtyEightSoTheUiDoesNotJumpFromThirtyFive() {
        assertThat(AnalysisController.basicAnalysisPercent(9, 9)).isEqualTo(38);
    }

    @Test
    void guardsAgainstDivisionByZeroAndClampsOutOfRangeInput() {
        assertThat(AnalysisController.basicAnalysisPercent(1, 1)).isEqualTo(10);
        assertThat(AnalysisController.basicAnalysisPercent(0, 9)).isEqualTo(10);
        assertThat(AnalysisController.basicAnalysisPercent(50, 9)).isEqualTo(38);
    }
}
