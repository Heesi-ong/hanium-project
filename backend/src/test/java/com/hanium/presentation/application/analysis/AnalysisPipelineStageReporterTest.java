package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.AnalysisStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AnalysisPipelineStageReporterTest {

    private static final String JOB_ID = "job-stage-reporter";

    private AnalysisJobStatusService statusService;
    private AnalysisProgressService progressService;
    private AnalysisPipelineStageReporter reporter;

    @BeforeEach
    void setUp() {
        statusService = mock(AnalysisJobStatusService.class);
        progressService = mock(AnalysisProgressService.class);
        reporter = new AnalysisPipelineStageReporter(statusService, progressService);
    }

    @Test
    void startInitializesOnlyProgressCache() {
        reporter.start(JOB_ID);

        verify(progressService).start(JOB_ID);
        verify(statusService, never()).updateStatus(JOB_ID, AnalysisStatus.UPLOADED);
    }

    @Test
    void basicAnalysisDoesNotRepeatStatusTransitionAlreadyPerformedByClaim() {
        int percent = reporter.beginBasicAnalysis(JOB_ID);

        assertThat(percent).isEqualTo(10);
        verify(statusService, never()).updateStatus(JOB_ID, AnalysisStatus.BASIC_ANALYZING);
        verify(progressService).update(
                JOB_ID,
                AnalysisStep.BASIC_ANALYSIS,
                AnalysisStatus.BASIC_ANALYZING,
                10,
                "영상/음성 기본 분석을 실행하는 중입니다."
        );
    }

    @Test
    void videoLlmTransitionStoresDatabaseStatusBeforeProgress() {
        assertTransition(
                reporter.beginVideoLlmAnalysis(JOB_ID),
                AnalysisStep.VIDEO_LLM_ANALYSIS,
                AnalysisStatus.VIDEO_LLM_ANALYZING,
                40,
                "Video LLM 분석을 실행하는 중입니다."
        );
    }

    @Test
    void compactTransitionStoresDatabaseStatusBeforeProgress() {
        assertTransition(
                reporter.beginCompacting(JOB_ID),
                AnalysisStep.COMPACT_ANALYSIS,
                AnalysisStatus.COMPACTING,
                60,
                "분석 결과를 정리하는 중입니다."
        );
    }

    @Test
    void openAiTransitionStoresDatabaseStatusBeforeProgress() {
        assertTransition(
                reporter.beginOpenAiFeedback(JOB_ID),
                AnalysisStep.OPENAI_FEEDBACK,
                AnalysisStatus.OPENAI_GENERATING,
                75,
                "AI 피드백을 생성하는 중입니다."
        );
    }

    @Test
    void mergeTransitionStoresDatabaseStatusBeforeProgress() {
        assertTransition(
                reporter.beginResultMerge(JOB_ID),
                AnalysisStep.RESULT_MERGE,
                AnalysisStatus.MERGING_RESULT,
                90,
                "최종 결과를 병합하는 중입니다."
        );
    }

    private void assertTransition(
            int actualPercent,
            AnalysisStep step,
            AnalysisStatus status,
            int expectedPercent,
            String message
    ) {
        assertThat(actualPercent).isEqualTo(expectedPercent);
        InOrder inOrder = inOrder(statusService, progressService);
        inOrder.verify(statusService).updateStatus(JOB_ID, status);
        inOrder.verify(progressService).update(JOB_ID, step, status, expectedPercent, message);
    }
}
