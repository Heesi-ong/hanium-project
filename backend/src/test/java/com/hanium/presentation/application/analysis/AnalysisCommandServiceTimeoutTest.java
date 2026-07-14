package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 작업별 하드 타임아웃 검증.
 *
 * <p>마감 시각을 과거로 만들면(테스트에서 timeout-minutes=-1), 파이프라인은 첫 체크포인트에서
 * 즉시 timeout으로 실패해야 합니다. 즉 analysis-engine을 호출하기도 전에 종료되고,
 * failed(reason=timeout) / duration(outcome=timeout) 메트릭이 기록되어야 합니다.</p>
 */
class AnalysisCommandServiceTimeoutTest {

    private static final String JOB_ID = "20260708120000-abcdef01";

    private SimpleMeterRegistry meterRegistry;
    private AnalysisJobRepository analysisJobRepository;
    private AnalysisEngineClient analysisEngineClient;
    private AnalysisCommandService analysisCommandService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        analysisJobRepository = mock(AnalysisJobRepository.class);
        UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);
        analysisEngineClient = mock(AnalysisEngineClient.class);

        ThreadPoolTaskExecutor analysisTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(analysisTaskExecutor).execute(any(Runnable.class));

        AnalysisJob analysisJob = mock(AnalysisJob.class);
        when(analysisJob.getJobId()).thenReturn(JOB_ID);
        when(analysisJob.getOwnerId()).thenReturn(1L);
        when(analysisJob.getStatus()).thenReturn(AnalysisStatus.BASIC_ANALYZING);
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(true);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(analysisJobRepository.saveAndFlush(analysisJob)).thenReturn(analysisJob);

        UploadedVideo uploadedVideo = mock(UploadedVideo.class);
        when(uploadedVideo.getStoredFilePath()).thenReturn("/storage/uploads/" + JOB_ID + "/video.mp4");
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        AnalysisJobStatusService analysisJobStatusService = mock(AnalysisJobStatusService.class);
        when(analysisJobStatusService.claimForExecution(JOB_ID)).thenReturn(true);

        analysisCommandService = new AnalysisCommandService(
                analysisJobRepository,
                uploadedVideoRepository,
                mock(VideoFileCommandService.class),
                mock(ResultCommandService.class),
                analysisEngineClient,
                mock(VideoLlmEngineClient.class),
                mock(OpenAiClient.class),
                mock(UserRateLimiter.class),
                mock(VideoDurationProbe.class),
                mock(JobIdGenerator.class),
                mock(AnalysisProgressService.class),
                analysisJobStatusService,
                analysisTaskExecutor,
                new AnalysisRetryProperties(3),
                new AnalysisQueueProperties(100, 3),
                meterRegistry
        );

        // 마감 시각을 과거로 만들어(now + (-1)분) 첫 체크포인트에서 즉시 timeout이 나게 합니다.
        ReflectionTestUtils.setField(analysisCommandService, "jobTimeoutMinutes", -1L);
    }

    @Test
    void failsWithTimeoutBeforeCallingEngineWhenDeadlineExceeded() {
        analysisCommandService.runAnalysis(JOB_ID, 1L, true, true);

        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "timeout").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("analysis.job.duration", "outcome", "timeout").count())
                .isEqualTo(1L);

        // 마감 초과로 기본 분석을 시작하기도 전에 종료되어야 합니다.
        verify(analysisEngineClient, never()).analyze(any(AnalysisEngineRequest.class));
    }
}
