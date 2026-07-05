package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AnalysisCommandService가 분석 작업의 시작/성공/실패/취소 메트릭을
 * 정확한 이름과 태그로 기록하는지 검증합니다.
 * MeterRegistry는 모킹하지 않고 SimpleMeterRegistry 실제 인스턴스를 사용합니다.
 */
class AnalysisCommandServiceMetricsTest {

    private static final String JOB_ID = "job-metrics-test";

    private SimpleMeterRegistry meterRegistry;
    private AnalysisJobRepository analysisJobRepository;
    private UploadedVideoRepository uploadedVideoRepository;
    private ResultCommandService resultCommandService;
    private AnalysisEngineClient analysisEngineClient;
    private AnalysisJob analysisJob;

    private AnalysisCommandService analysisCommandService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        analysisJobRepository = mock(AnalysisJobRepository.class);
        uploadedVideoRepository = mock(UploadedVideoRepository.class);
        resultCommandService = mock(ResultCommandService.class);
        analysisEngineClient = mock(AnalysisEngineClient.class);

        // execute(Runnable)를 즉시 동기 실행해 비동기 타이밍 문제 없이 바로 검증합니다.
        ThreadPoolTaskExecutor analysisTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(analysisTaskExecutor).execute(any(Runnable.class));

        analysisJob = mock(AnalysisJob.class);
        when(analysisJob.getJobId()).thenReturn(JOB_ID);
        when(analysisJob.getOwnerId()).thenReturn(1L);
        when(analysisJob.getStatus()).thenReturn(AnalysisStatus.BASIC_ANALYZING);
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(true);
        when(analysisJob.isCancelRequested()).thenReturn(false);

        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(analysisJobRepository.saveAndFlush(analysisJob)).thenReturn(analysisJob);

        UploadedVideo uploadedVideo = mock(UploadedVideo.class);
        when(uploadedVideo.getStoredFilePath()).thenReturn("/storage/uploads/" + JOB_ID + "/video.mp4");
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        when(resultCommandService.saveEngineResultsAndCompact(anyString(), any(), any()))
                .thenReturn(Map.of());

        analysisCommandService = new AnalysisCommandService(
                analysisJobRepository,
                uploadedVideoRepository,
                mock(VideoFileCommandService.class),
                resultCommandService,
                analysisEngineClient,
                mock(VideoLlmEngineClient.class),
                mock(OpenAiClient.class),
                mock(JobIdGenerator.class),
                mock(AnalysisProgressService.class),
                mock(AnalysisJobStatusService.class),
                analysisTaskExecutor,
                new AnalysisRetryProperties(3),
                meterRegistry
        );
    }

    @Test
    void successPathRecordsStartedCompletedAndDurationMetrics() {
        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenReturn(successEngineResponse());

        analysisCommandService.runAnalysis(JOB_ID, 1L, false, false);

        assertThat(meterRegistry.counter("analysis.job.started", "trigger", "run").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("analysis.job.completed").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "completed").count())
                .isEqualTo(1L);
    }

    @Test
    void failurePathRecordsFailedCounterWithBusinessReasonAndFailedDuration() {
        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenThrow(new BusinessException(
                        ErrorCode.ANALYSIS_ENGINE_ERROR,
                        "기본 분석 엔진 호출 중 오류가 발생했습니다."
                ));

        analysisCommandService.runAnalysis(JOB_ID, 1L, false, false);

        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "business").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "failed").count())
                .isEqualTo(1L);
        assertThat(meterRegistry.counter("analysis.job.completed").count())
                .isEqualTo(0.0);
    }

    @Test
    void unexpectedFailurePathRecordsFailedCounterWithUnexpectedReason() {
        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenThrow(new RuntimeException("연결이 끊어졌습니다."));

        analysisCommandService.runAnalysis(JOB_ID, 1L, false, false);

        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "unexpected").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "failed").count())
                .isEqualTo(1L);
    }

    @Test
    void cancelledPathRecordsCancelledCounterAndCancelledDuration() {
        // 백그라운드 파이프라인이 첫 취소 확인 지점에서 취소를 감지하도록 스텁합니다.
        when(analysisJob.isCancelRequested()).thenReturn(true);

        analysisCommandService.runAnalysis(JOB_ID, 1L, false, false);

        assertThat(meterRegistry.counter("analysis.job.cancelled").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "cancelled").count())
                .isEqualTo(1L);
        assertThat(meterRegistry.counter("analysis.job.completed").count())
                .isEqualTo(0.0);
    }

    @Test
    void retryPathRecordsStartedCounterWithRetryTrigger() {
        when(analysisJob.canRetry()).thenReturn(true);
        when(analysisJob.getRetryCount()).thenReturn(0);
        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenReturn(successEngineResponse());

        analysisCommandService.retryAnalysis(JOB_ID, 1L, false, false);

        assertThat(meterRegistry.counter("analysis.job.started", "trigger", "retry").count())
                .isEqualTo(1.0);
    }

    private AnalysisEngineResponse successEngineResponse() {
        return new AnalysisEngineResponse(
                JOB_ID,
                "completed",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }
}
