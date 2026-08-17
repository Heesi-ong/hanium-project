package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisPipelineTerminationHandlerTest {

    private static final String JOB_ID = "job-termination-test";
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    private AnalysisJobRepository analysisJobRepository;
    private AnalysisJobStatusService analysisJobStatusService;
    private AnalysisProgressService analysisProgressService;
    private ResultCommandService resultCommandService;
    private SimpleMeterRegistry meterRegistry;
    private AnalysisPipelineTerminationHandler handler;

    @BeforeEach
    void setUp() {
        analysisJobRepository = mock(AnalysisJobRepository.class);
        analysisJobStatusService = mock(AnalysisJobStatusService.class);
        analysisProgressService = mock(AnalysisProgressService.class);
        resultCommandService = mock(ResultCommandService.class);
        meterRegistry = new SimpleMeterRegistry();
        handler = new AnalysisPipelineTerminationHandler(
                analysisJobRepository,
                analysisJobStatusService,
                analysisProgressService,
                resultCommandService,
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(analysisJobStatusService.failStatus(
                JOB_ID,
                "분석이 제한 시간(20분)을 초과해 자동으로 종료되었습니다."
        )).thenReturn(true);
        when(analysisJobStatusService.cancelStatus(JOB_ID)).thenReturn(true);
    }

    @Test
    void timeoutTakesPriorityWithoutReadingCancellationFlag() {
        Timer.Sample sample = Timer.start(meterRegistry);

        boolean stopped = handler.stopIfCancelledOrTimedOut(
                JOB_ID,
                60,
                sample,
                NOW.minusSeconds(1),
                20
        );

        String reason = "분석이 제한 시간(20분)을 초과해 자동으로 종료되었습니다.";
        assertThat(stopped).isTrue();
        verifyNoInteractions(analysisJobRepository);
        verify(analysisJobStatusService).failStatus(JOB_ID, reason);
        verify(analysisProgressService).fail(JOB_ID, 60, reason);
        verify(resultCommandService).saveFailureResult(JOB_ID, "FAILED", reason);
        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "timeout").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "timeout").count())
                .isEqualTo(1L);
    }

    @Test
    void cancellationUpdatesStatusProgressResultAndMetrics() {
        AnalysisJob analysisJob = mock(AnalysisJob.class);
        when(analysisJob.isCancelRequested()).thenReturn(true);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        Timer.Sample sample = Timer.start(meterRegistry);

        boolean stopped = handler.stopIfCancelledOrTimedOut(
                JOB_ID,
                40,
                sample,
                NOW.plusSeconds(1),
                20
        );

        assertThat(stopped).isTrue();
        verify(analysisJobStatusService).cancelStatus(JOB_ID);
        verify(analysisProgressService).cancel(JOB_ID, 40);
        verify(resultCommandService).saveFailureResult(
                JOB_ID,
                "CANCELLED",
                "사용자 요청으로 분석 작업이 취소되었습니다."
        );
        assertThat(meterRegistry.counter("analysis.job.cancelled").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "cancelled").count())
                .isEqualTo(1L);
    }

    @Test
    void activeJobPassesCheckpointWithoutTerminationSideEffects() {
        AnalysisJob analysisJob = mock(AnalysisJob.class);
        when(analysisJob.isCancelRequested()).thenReturn(false);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));

        boolean stopped = handler.stopIfCancelledOrTimedOut(
                JOB_ID,
                10,
                Timer.start(meterRegistry),
                NOW.plusSeconds(1),
                20
        );

        assertThat(stopped).isFalse();
        verifyNoInteractions(analysisJobStatusService, analysisProgressService, resultCommandService);
        assertThat(meterRegistry.find("analysis.job.failed").counter()).isNull();
        assertThat(meterRegistry.find("analysis.job.cancelled").counter()).isNull();
        assertThat(meterRegistry.find("analysis.job.duration").timer()).isNull();
    }

    @Test
    void exactDeadlineDoesNotTimeoutAndStillChecksCancellation() {
        AnalysisJob analysisJob = mock(AnalysisJob.class);
        when(analysisJob.isCancelRequested()).thenReturn(false);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));

        boolean stopped = handler.stopIfCancelledOrTimedOut(
                JOB_ID,
                10,
                Timer.start(meterRegistry),
                NOW,
                20
        );

        assertThat(stopped).isFalse();
        verify(analysisJobRepository).findByJobId(JOB_ID);
        verifyNoInteractions(analysisJobStatusService, analysisProgressService, resultCommandService);
    }

    @Test
    void resultWriteFailureDoesNotHideCancellationOrMetrics() {
        AnalysisJob analysisJob = mock(AnalysisJob.class);
        when(analysisJob.isCancelRequested()).thenReturn(true);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        doThrow(new RuntimeException("result storage unavailable"))
                .when(resultCommandService)
                .saveFailureResult(JOB_ID, "CANCELLED", "사용자 요청으로 분석 작업이 취소되었습니다.");

        boolean stopped = handler.stopIfCancelledOrTimedOut(
                JOB_ID,
                75,
                Timer.start(meterRegistry),
                NOW.plusSeconds(1),
                20
        );

        assertThat(stopped).isTrue();
        verify(analysisJobStatusService).cancelStatus(JOB_ID);
        verify(analysisProgressService).cancel(JOB_ID, 75);
        assertThat(meterRegistry.counter("analysis.job.cancelled").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "cancelled").count())
                .isEqualTo(1L);
        verify(analysisJobStatusService, never()).failStatus(JOB_ID, "result storage unavailable");
    }
}
