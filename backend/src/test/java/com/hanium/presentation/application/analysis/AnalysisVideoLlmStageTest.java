package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisVideoLlmStageTest {

    private static final String JOB_ID = "job-video-llm-stage-test";
    private static final String VIDEO_PATH = "/storage/uploads/job-video-llm-stage-test/video.mp4";
    private static final Long OWNER_ID = 7L;

    private AnalysisJobRepository analysisJobRepository;
    private UserRateLimiter userRateLimiter;
    private VideoDurationProbe videoDurationProbe;
    private VideoLlmEngineClient videoLlmEngineClient;
    private AnalysisJob analysisJob;
    private AnalysisVideoLlmStage stage;

    @BeforeEach
    void setUp() {
        analysisJobRepository = mock(AnalysisJobRepository.class);
        userRateLimiter = mock(UserRateLimiter.class);
        videoDurationProbe = mock(VideoDurationProbe.class);
        videoLlmEngineClient = mock(VideoLlmEngineClient.class);
        analysisJob = mock(AnalysisJob.class);

        when(analysisJob.getOwnerId()).thenReturn(OWNER_ID);
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.STANDARD);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.RESERVED);

        stage = new AnalysisVideoLlmStage(
                analysisJobRepository,
                userRateLimiter,
                videoDurationProbe,
                videoLlmEngineClient,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void disabledStandardJobCreatesSkippedPlanWithoutDurationBudgetOrProviderCall() {
        AnalysisVideoLlmStage.Plan plan = prepare(false);

        assertThat(plan.skipped()).isTrue();
        assertThat(plan.durationSec()).isNull();
        assertThat(plan.requireReal()).isFalse();
        assertThat(plan.skippedResponse().status()).isEqualTo("skipped");
        assertThat(plan.skippedResponse().model())
                .containsEntry("name", "video-llm-skipped")
                .containsEntry("generationMode", "SKIPPED");
        assertThat(plan.skippedResponse().globalSummary().get("mainStrength").toString())
                .contains("비활성화");
        verifyNoInteractions(videoDurationProbe, userRateLimiter, videoLlmEngineClient);
    }

    @Test
    void dailyBudgetDenialComesFromSingleAtomicReservation() {
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED);

        AnalysisVideoLlmStage.Plan plan = prepare(true);

        assertThat(plan.skipped()).isTrue();
        assertThat(plan.skippedResponse().globalSummary().get("mainStrength").toString())
                .contains("일일 한도");
        verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 18);
        verifyNoInteractions(videoLlmEngineClient);
    }

    @Test
    void monthlyBudgetDenialComesFromSingleAtomicReservation() {
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED);

        AnalysisVideoLlmStage.Plan plan = prepare(true);

        assertThat(plan.skipped()).isTrue();
        assertThat(plan.skippedResponse().globalSummary().get("mainStrength").toString())
                .contains("월간 한도");
        verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 18);
        verifyNoInteractions(videoLlmEngineClient);
    }

    @Test
    void durationDeterminesMonthlyPermitsAndProviderRequest() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenReturn(Optional.of(Duration.ofSeconds(250)));
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 3))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.RESERVED);
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenReturn(response("MOCK"));

        AnalysisVideoLlmStage.Plan plan = prepare(true);
        VideoLlmEngineResponse response = stage.analyze(
                JOB_ID,
                VIDEO_PATH,
                "http://backend/video",
                plan
        );

        verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 3);
        ArgumentCaptor<VideoLlmEngineRequest> requestCaptor =
                ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        verify(videoLlmEngineClient).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue().durationSec()).isEqualTo(250.0);
        assertThat(requestCaptor.getValue().videoDownloadUrl()).isEqualTo("http://backend/video");
        assertThat(requestCaptor.getValue().requireReal()).isFalse();
        assertThat(response.model()).containsEntry("generationMode", "MOCK");
    }

    @Test
    void durationProbeFailureUsesMaximumConservativePermitsAndNullDuration() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenThrow(new IllegalStateException("ffprobe unavailable"));
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenReturn(response("MOCK"));

        AnalysisVideoLlmStage.Plan plan = prepare(true);
        stage.analyze(JOB_ID, VIDEO_PATH, "http://backend/video", plan);

        verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 18);
        ArgumentCaptor<VideoLlmEngineRequest> requestCaptor =
                ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        verify(videoLlmEngineClient).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue().durationSec()).isNull();
    }

    @Test
    void disabledReanalysisFailsWithRealRequiredWithoutProviderCall() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);

        assertThatThrownBy(() -> prepare(false))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_REAL_REQUIRED)
                );

        verifyNoInteractions(videoDurationProbe, userRateLimiter, videoLlmEngineClient);
    }

    @Test
    void budgetDeniedReanalysisFailsWithUsageLimitInsteadOfReturningSkippedData() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED);

        assertThatThrownBy(() -> prepare(true))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VIDEO_LLM_USAGE_LIMIT_EXCEEDED)
                );

        verifyNoInteractions(videoLlmEngineClient);
    }

    @Test
    void reanalysisRequestRequiresRealProviderResponse() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenReturn(response("FALLBACK"));
        AnalysisVideoLlmStage.Plan plan = prepare(true);

        assertThat(plan.requireReal()).isTrue();
        assertThatThrownBy(() -> stage.analyze(
                JOB_ID,
                VIDEO_PATH,
                "http://backend/video",
                plan
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_REAL_REQUIRED)
        );

        ArgumentCaptor<VideoLlmEngineRequest> requestCaptor =
                ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        verify(videoLlmEngineClient).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue().requireReal()).isTrue();
    }

    private AnalysisVideoLlmStage.Plan prepare(boolean useVideoLlm) {
        return stage.prepare(JOB_ID, useVideoLlm, VIDEO_PATH, 100.0, 30);
    }

    private VideoLlmEngineResponse response(String generationMode) {
        return new VideoLlmEngineResponse(
                JOB_ID,
                "success",
                Map.of("generationMode", generationMode),
                Map.of(),
                Map.of()
        );
    }
}
