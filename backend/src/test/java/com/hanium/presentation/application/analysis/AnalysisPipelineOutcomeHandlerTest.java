package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisPipelineOutcomeHandlerTest {

    private static final String JOB_ID = "pipeline-outcome-job";

    private AnalysisJobStatusService analysisJobStatusService;
    private AnalysisProgressService analysisProgressService;
    private AnalysisResultPersistenceStage resultPersistenceStage;
    private SimpleMeterRegistry meterRegistry;
    private AnalysisPipelineOutcomeHandler handler;

    @BeforeEach
    void setUp() {
        analysisJobStatusService = mock(AnalysisJobStatusService.class);
        analysisProgressService = mock(AnalysisProgressService.class);
        resultPersistenceStage = mock(AnalysisResultPersistenceStage.class);
        meterRegistry = new SimpleMeterRegistry();
        handler = new AnalysisPipelineOutcomeHandler(
                analysisJobStatusService,
                analysisProgressService,
                resultPersistenceStage,
                meterRegistry
        );
        when(analysisJobStatusService.completeStatus(JOB_ID, VideoLlmGenerationMode.SKIPPED))
                .thenReturn(true);
        when(analysisJobStatusService.failStatus(JOB_ID, "provider 실패")).thenReturn(true);
        when(analysisJobStatusService.failStatus(JOB_ID, "워커 대기열 포화")).thenReturn(true);
    }

    @Test
    void completePersistsStatusBeforeProgressAndRecordsMetrics() {
        handler.complete(
                JOB_ID,
                VideoLlmGenerationMode.SKIPPED,
                Timer.start(meterRegistry)
        );

        InOrder order = inOrder(analysisJobStatusService, analysisProgressService);
        order.verify(analysisJobStatusService)
                .completeStatus(JOB_ID, VideoLlmGenerationMode.SKIPPED);
        order.verify(analysisProgressService).complete(JOB_ID);
        verifyNoInteractions(resultPersistenceStage);
        assertThat(meterRegistry.counter("analysis.job.completed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "completed").count())
                .isEqualTo(1L);
    }

    @Test
    void runningFailurePreservesStatusProgressResultOrderAndReasonTags() {
        handler.fail(
                JOB_ID,
                60,
                "provider 실패",
                "business",
                Timer.start(meterRegistry)
        );

        InOrder order = inOrder(
                analysisJobStatusService,
                analysisProgressService,
                resultPersistenceStage
        );
        order.verify(analysisJobStatusService).failStatus(JOB_ID, "provider 실패");
        order.verify(analysisProgressService).fail(JOB_ID, 60, "provider 실패");
        order.verify(resultPersistenceStage).saveFailureSafely(JOB_ID, "provider 실패");
        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "business").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "failed").count())
                .isEqualTo(1L);
    }

    @Test
    void preExecutionFailureDoesNotCreateDurationTimer() {
        handler.failBeforeExecution(
                JOB_ID,
                0,
                "워커 대기열 포화",
                "queue-full"
        );

        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "queue-full").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("analysis.job.duration").timer()).isNull();
    }

    @Test
    void skippedClaimOnlyStopsTimerWithSkippedOutcome() {
        handler.stopSkipped(Timer.start(meterRegistry));

        verifyNoInteractions(
                analysisJobStatusService,
                analysisProgressService,
                resultPersistenceStage
        );
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "skipped").count())
                .isEqualTo(1L);
    }
}
