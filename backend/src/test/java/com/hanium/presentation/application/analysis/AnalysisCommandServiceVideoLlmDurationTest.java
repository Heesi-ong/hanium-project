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
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private AnalysisEngineClient analysisEngineClient;

    @BeforeEach
    void setUp() {
        AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
        UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);
        resultCommandService = mock(ResultCommandService.class);
        analysisEngineClient = mock(AnalysisEngineClient.class);
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
        when(analysisJobStatusService.completeStatus(eq(JOB_ID), any())).thenReturn(true);
        when(analysisJobStatusService.failStatus(eq(JOB_ID), anyString())).thenReturn(true);

        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenReturn(successEngineResponse());
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenAnswer(invocation -> videoLlmResponse(invocation.getArgument(0, VideoLlmEngineRequest.class).jobId()));
        when(userRateLimiter.reserveVideoLlmBudget(
                eq(1L),
                anyString(),
                anyInt()
        )).thenReturn(UserRateLimiter.VideoLlmBudgetReservation.RESERVED);
        when(resultCommandService.saveEngineResultsAndCompact(anyString(), any(), any()))
                .thenReturn(Map.of());

        analysisCommandService = new AnalysisCommandService(
                analysisJobRepository,
                mock(com.hanium.presentation.domain.user.repository.UserRepository.class),
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
                new AnalysisQueueProperties(100, 3),
                new SimpleMeterRegistry(),
                new AnalysisJobValidator(new AnalysisRetryProperties(3))
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
        when(userRateLimiter.reserveVideoLlmBudget(
                eq(1L),
                anyString(),
                anyInt()
        )).thenReturn(UserRateLimiter.VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED);

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));
        // 단일 원자 예약이 MONTHLY_LIMIT_EXCEEDED를 반환하면 Redis Lua가 두 키를 모두
        // 변경하지 않으므로 사용자 일일 permit도 헛되이 소모되지 않습니다.
        verify(userRateLimiter).reserveVideoLlmBudget(eq(1L), anyString(), anyInt());

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
        when(userRateLimiter.reserveVideoLlmBudget(
                eq(1L),
                anyString(),
                anyInt()
        )).thenReturn(UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED);

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));
        verify(userRateLimiter).reserveVideoLlmBudget(eq(1L), anyString(), anyInt());

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

        verify(userRateLimiter, never()).reserveVideoLlmBudget(
                any(Long.class),
                anyString(),
                anyInt()
        );
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
    void doesNotReserveVideoLlmBudgetWhenCancelledDuringBasicAnalysis() {
        // basic 분석이 끝난 직후 취소가 감지되면, Video LLM 예산을 차감하는 prepare()에
        // 도달하기 전 체크포인트에서 파이프라인이 멈춰야 합니다. (예약을 되돌리는 경로가
        // 없으므로, 애초에 예약하지 않는 것이 유일한 방어선입니다.)
        when(analysisJob.isCancelRequested()).thenReturn(false);
        when(analysisJobStatusService.cancelStatus(JOB_ID)).thenReturn(true);
        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class))).thenAnswer(invocation -> {
            when(analysisJob.isCancelRequested()).thenReturn(true);
            return successEngineResponse();
        });

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        verify(userRateLimiter, never()).reserveVideoLlmBudget(any(Long.class), anyString(), anyInt());
        verify(videoLlmEngineClient, never()).analyze(any(VideoLlmEngineRequest.class));
        verify(resultCommandService, never()).saveEngineResultsAndCompact(anyString(), any(), any());
    }

    @Test
    void reservesMaximumMonthlyCallUnitsWhenDurationProbeFailsOpen() {
        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        String currentMonth = YearMonth.now().toString();
        verify(userRateLimiter).reserveVideoLlmBudget(1L, currentMonth, 18);
        verify(videoLlmEngineClient).analyze(any(VideoLlmEngineRequest.class));
    }

    @Test
    void reservesOneMonthlyCallUnitPerExpectedVideoSegment() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenReturn(Optional.of(Duration.ofSeconds(250)));

        analysisCommandService.runAnalysis(JOB_ID, 1L, true, false);

        String currentMonth = YearMonth.now().toString();
        verify(userRateLimiter).reserveVideoLlmBudget(1L, currentMonth, 3);
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
                "success",
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
