package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * dispatchClaimedJob()이 "이미 claim된 작업"을 실행할 때, claimForExecution()을 다시
 * 호출하지 않고도 파이프라인이 정상적으로 끝까지 실행되는지 검증합니다.
 *
 * <p>QueuedAnalysisJobPoller가 claimNextQueuedJobs()로 원자적으로 선점을 마친 뒤 이
 * 메서드를 호출하므로, 여기서 claimForExecution()을 또 호출하면(중복 조회) "조회 후 실행
 * 제출"로 되돌아가는 셈이라 이 태스크의 목표(claim 후 실행 제출)가 무의미해집니다.</p>
 */
class AnalysisCommandServiceDispatchClaimedJobTest {

    private static final String JOB_ID = "20260709120000-cccccccc";

    private SimpleMeterRegistry meterRegistry;
    private AnalysisJobStatusService analysisJobStatusService;
    private AnalysisEngineClient analysisEngineClient;
    private AnalysisCommandService analysisCommandService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        analysisJobStatusService = mock(AnalysisJobStatusService.class);
        analysisEngineClient = mock(AnalysisEngineClient.class);

        UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);
        UploadedVideo uploadedVideo = mock(UploadedVideo.class);
        when(uploadedVideo.getStoredFilePath()).thenReturn("/storage/uploads/" + JOB_ID + "/video.mp4");
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenReturn(successEngineResponse());

        ThreadPoolTaskExecutor analysisTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(analysisTaskExecutor).execute(any(Runnable.class));

        analysisCommandService = new AnalysisCommandService(
                mock(com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository.class),
                uploadedVideoRepository,
                mock(VideoFileCommandService.class),
                mock(ResultCommandService.class),
                analysisEngineClient,
                mock(VideoLlmEngineClient.class),
                mock(OpenAiClient.class),
                mock(VideoDurationProbe.class),
                mock(JobIdGenerator.class),
                mock(AnalysisProgressService.class),
                analysisJobStatusService,
                analysisTaskExecutor,
                new AnalysisRetryProperties(3),
                new AnalysisQueueProperties(100, 3),
                meterRegistry
        );
    }

    @Test
    void dispatchClaimedJobSkipsClaimForExecutionAndStillRunsThePipeline() {
        analysisCommandService.dispatchClaimedJob(JOB_ID, false, false);

        verify(analysisJobStatusService, never()).claimForExecution(JOB_ID);
        verify(analysisEngineClient).analyze(any(AnalysisEngineRequest.class));
        assertThat(meterRegistry.counter("analysis.job.completed").count()).isEqualTo(1.0);
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
