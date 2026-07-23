package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
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
    private AnalysisJob analysisJob;
    private AnalysisJobStatusService analysisJobStatusService;

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
        when(uploadedVideo.getStoredFilePath()).thenReturn(VIDEO_PATH);
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        analysisJobStatusService = mock(AnalysisJobStatusService.class);
        when(analysisJobStatusService.claimForExecution(JOB_ID)).thenReturn(true);

        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenReturn(successEngineResponse());
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenAnswer(invocation -> videoLlmResponse(invocation.getArgument(0, VideoLlmEngineRequest.class).jobId()));
        when(userRateLimiter.wouldAllow(eq("video-llm-monthly"), anyString())).thenReturn(true);
        when(userRateLimiter.wouldAllow(eq("video-llm-daily"), any(Long.class))).thenReturn(true);
        when(userRateLimiter.tryConsume(eq("video-llm-monthly"), anyString())).thenReturn(true);
        when(userRateLimiter.tryConsume(eq("video-llm-daily"), any(Long.class))).thenReturn(true);
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
        when(userRateLimiter.wouldAllow(eq("video-llm-monthly"), anyString())).thenReturn(false);

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));
        // 월간 예산이 이미 소진된 것으로 확인(peek)되면, 일일/월간 어느 쪽도 실제로
        // 소비(tryConsume)하지 않아야 합니다 - 사용자의 일일 한도를 헛되이 낭비하지
        // 않는 것이 이번 수정의 핵심입니다.
        verify(userRateLimiter, never()).tryConsume(eq("video-llm-daily"), any(Long.class));
        verify(userRateLimiter, never()).tryConsume(eq("video-llm-monthly"), anyString());

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
    void skipsVideoLlmEngineCallWhenDailyBudgetExceeded() {
        when(userRateLimiter.wouldAllow(eq("video-llm-daily"), any(Long.class))).thenReturn(false);

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));
        // 일일 한도로 이미 막혔으므로(peek 단계) 공용 월간 카운터는 peek조차 하지 않고,
        // 어느 쪽도 실제로 소비하지 않아야 합니다.
        verify(userRateLimiter, never()).wouldAllow(eq("video-llm-monthly"), anyString());
        verify(userRateLimiter, never()).tryConsume(eq("video-llm-daily"), any(Long.class));
        verify(userRateLimiter, never()).tryConsume(eq("video-llm-monthly"), anyString());

        ArgumentCaptor<VideoLlmEngineResponse> captor = ArgumentCaptor.forClass(VideoLlmEngineResponse.class);
        verify(resultCommandService).saveEngineResultsAndCompact(eq(JOB_ID), any(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("skipped");
        assertThat(captor.getValue().model()).containsEntry("name", "video-llm-skipped");
        assertThat(captor.getValue().model()).containsEntry("generationMode", "SKIPPED");
        assertThat(captor.getValue().globalSummary().get("visualDelivery").toString())
                .contains("호출 한도");
        assertThat(captor.getValue().globalSummary().get("mainStrength").toString())
                .contains("일일 한도");
    }

    @Test
    void skipsVideoLlmEngineCallWithDisabledReasonWhenUserTurnsOffVideoLlm() {
        analysisCommandService.runAnalysis(JOB_ID, 1L, false, false);

        verify(userRateLimiter, never()).wouldAllow(eq("video-llm-daily"), any(Long.class));
        verify(userRateLimiter, never()).wouldAllow(eq("video-llm-monthly"), anyString());
        verify(userRateLimiter, never()).tryConsume(eq("video-llm-daily"), any(Long.class));
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

        verify(userRateLimiter).wouldAllow(eq("video-llm-daily"), any(Long.class));
        verify(userRateLimiter).wouldAllow(eq("video-llm-monthly"), anyString());
        verify(userRateLimiter).tryConsume(eq("video-llm-daily"), any(Long.class));
        verify(userRateLimiter).tryConsume(eq("video-llm-monthly"), anyString());
        verify(videoLlmEngineClient).analyze(any(VideoLlmEngineRequest.class));
    }

    @Test
    void requiresRealVideoLlmForReanalysisJob() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenAnswer(invocation -> videoLlmResponse(
                        invocation.getArgument(0, VideoLlmEngineRequest.class).jobId(),
                        "REAL"
                ));

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        ArgumentCaptor<VideoLlmEngineRequest> captor =
                ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        verify(videoLlmEngineClient).analyze(captor.capture());
        assertThat(captor.getValue().requireReal()).isTrue();
    }

    @Test
    void failsReanalysisJobWhenEngineReturnsNonRealResponse() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(analysisJobStatusService).failStatus(
                eq(JOB_ID),
                org.mockito.ArgumentMatchers.contains("REAL 응답")
        );
        verify(resultCommandService, never()).saveEngineResultsAndCompact(anyString(), any(), any());
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
        return videoLlmResponse(jobId, "MOCK");
    }

    private VideoLlmEngineResponse videoLlmResponse(String jobId, String generationMode) {
        return new VideoLlmEngineResponse(
                jobId,
                "success",
                Map.of("generationMode", generationMode),
                Map.of(),
                Map.of()
        );
    }
}
