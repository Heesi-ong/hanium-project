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
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisCommandServiceVideoLlmDurationTest {

    private static final String JOB_ID = "duration-job";
    private static final String VIDEO_PATH = "/storage/uploads/duration-job/video.mp4";

    private AnalysisCommandService analysisCommandService;
    private VideoDurationProbe videoDurationProbe;
    private VideoLlmEngineClient videoLlmEngineClient;
    private ResultCommandService resultCommandService;
    private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() {
        AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
        UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);
        resultCommandService = mock(ResultCommandService.class);
        AnalysisEngineClient analysisEngineClient = mock(AnalysisEngineClient.class);
        videoLlmEngineClient = mock(VideoLlmEngineClient.class);
        videoDurationProbe = mock(VideoDurationProbe.class);
        userRateLimiter = mock(UserRateLimiter.class);

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
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(true);
        when(analysisJob.isCancelRequested()).thenReturn(false);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(analysisJobRepository.saveAndFlush(analysisJob)).thenReturn(analysisJob);

        UploadedVideo uploadedVideo = mock(UploadedVideo.class);
        when(uploadedVideo.getStoredFilePath()).thenReturn(VIDEO_PATH);
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        AnalysisJobStatusService analysisJobStatusService = mock(AnalysisJobStatusService.class);
        when(analysisJobStatusService.claimForExecution(JOB_ID)).thenReturn(true);

        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenReturn(successEngineResponse());
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenAnswer(invocation -> videoLlmResponse(invocation.getArgument(0, VideoLlmEngineRequest.class).jobId()));
        when(userRateLimiter.tryConsume(eq("video-llm-monthly"), anyString())).thenReturn(true);
        when(resultCommandService.saveEngineResultsAndCompact(anyString(), any(), any()))
                .thenReturn(Map.of());

        analysisCommandService = new AnalysisCommandService(
                analysisJobRepository,
                uploadedVideoRepository,
                mock(VideoFileCommandService.class),
                resultCommandService,
                analysisEngineClient,
                videoLlmEngineClient,
                mock(OpenAiClient.class),
                userRateLimiter,
                videoDurationProbe,
                mock(JobIdGenerator.class),
                mock(AnalysisProgressService.class),
                analysisJobStatusService,
                analysisTaskExecutor,
                new AnalysisRetryProperties(3),
                new AnalysisQueueProperties(100, 3),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void sendsDurationSecToVideoLlmEngineWhenProbeSucceeds() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenReturn(Optional.of(Duration.ofMillis(4166)));

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        ArgumentCaptor<VideoLlmEngineRequest> captor = ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        org.mockito.Mockito.verify(videoLlmEngineClient).analyze(captor.capture());
        assertThat(captor.getValue().durationSec()).isEqualTo(4.166);
    }

    @Test
    void sendsNullDurationSecToVideoLlmEngineWhenProbeFailsOpen() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenReturn(Optional.empty());

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        ArgumentCaptor<VideoLlmEngineRequest> captor = ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        org.mockito.Mockito.verify(videoLlmEngineClient).analyze(captor.capture());
        assertThat(captor.getValue().durationSec()).isNull();
    }

    @Test
    void skipsVideoLlmEngineCallWhenMonthlyBudgetExceeded() {
        when(userRateLimiter.tryConsume(eq("video-llm-monthly"), anyString())).thenReturn(false);

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));

        ArgumentCaptor<VideoLlmEngineResponse> captor = ArgumentCaptor.forClass(VideoLlmEngineResponse.class);
        verify(resultCommandService).saveEngineResultsAndCompact(eq(JOB_ID), any(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("skipped");
        assertThat(captor.getValue().model()).containsEntry("name", "video-llm-skipped");
        assertThat(captor.getValue().model()).containsEntry("generationMode", "SKIPPED");
        assertThat(captor.getValue().globalSummary().get("visualDelivery").toString())
                .contains("호출 한도");
        assertThat(captor.getValue().globalSummary().get("mainStrength").toString())
                .contains("월간 한도");
    }

    @Test
    void skipsVideoLlmEngineCallWithDisabledReasonWhenUserTurnsOffVideoLlm() {
        analysisCommandService.runAnalysis(JOB_ID, 1L, false, false);

        verify(userRateLimiter, never()).tryConsume(eq("video-llm-monthly"), anyString());
        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));

        ArgumentCaptor<VideoLlmEngineResponse> captor = ArgumentCaptor.forClass(VideoLlmEngineResponse.class);
        verify(resultCommandService).saveEngineResultsAndCompact(eq(JOB_ID), any(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("skipped");
        assertThat(captor.getValue().model()).containsEntry("generationMode", "SKIPPED");
        assertThat(captor.getValue().globalSummary().get("visualDelivery").toString())
                .contains("설정");
        assertThat(captor.getValue().globalSummary().get("mainStrength").toString())
                .contains("비활성화");
    }

    @Test
    void consumesMonthlyBudgetAndCallsVideoLlmEngineWhenBudgetAllows() {
        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(userRateLimiter).tryConsume(eq("video-llm-monthly"), anyString());
        verify(videoLlmEngineClient).analyze(any(VideoLlmEngineRequest.class));
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

    private VideoLlmEngineResponse videoLlmResponse(String jobId) {
        return new VideoLlmEngineResponse(
                jobId,
                "success",
                Map.of("generationMode", "MOCK"),
                Map.of(),
                Map.of()
        );
    }
}
