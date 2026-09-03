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
    void prepareDoesNotTouchDurationBudgetOrProviderForDisabledStandardJob() {
        AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, false, VIDEO_PATH);

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
    void prepareDoesNotReserveBudget() {
        stage.prepare(JOB_ID, true, VIDEO_PATH);

        verifyNoInteractions(userRateLimiter, videoLlmEngineClient);
    }

    @Test
    void reserveBudgetOrSkipReturnsSkipResponseWhenDailyLimitExceeded() {
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED);
        AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, true, VIDEO_PATH);

        Optional<VideoLlmEngineResponse> skip = reserveBudgetOrSkip(plan);

        assertThat(skip).isPresent();
        assertThat(skip.get().globalSummary().get("mainStrength").toString()).contains("일일 한도");
        verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 18);
        verifyNoInteractions(videoLlmEngineClient);
    }

    @Test
    void reserveBudgetOrSkipReturnsSkipResponseWhenMonthlyLimitExceeded() {
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED);
        AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, true, VIDEO_PATH);

        Optional<VideoLlmEngineResponse> skip = reserveBudgetOrSkip(plan);

        assertThat(skip).isPresent();
        assertThat(skip.get().globalSummary().get("mainStrength").toString()).contains("월간 한도");
        verifyNoInteractions(videoLlmEngineClient);
    }

    @Test
    void reserveBudgetOrSkipIsEmptyAndDurationDrivesPermitsAndProviderRequest() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenReturn(Optional.of(Duration.ofSeconds(250)));
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 3))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.RESERVED);
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenReturn(response("MOCK"));

        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger stageLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AnalysisVideoLlmStage.class);
        stageLogger.addAppender(appender);

        try {
            AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, true, VIDEO_PATH);
            assertThat(reserveBudgetOrSkip(plan)).isEmpty();

            VideoLlmEngineResponse response = stage.analyze(
                    JOB_ID, VIDEO_PATH, "http://backend/video", plan);

            verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 3);
            ArgumentCaptor<VideoLlmEngineRequest> requestCaptor =
                    ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
            verify(videoLlmEngineClient).analyze(requestCaptor.capture());
            assertThat(requestCaptor.getValue().durationSec()).isEqualTo(250.0);
            assertThat(requestCaptor.getValue().videoDownloadUrl()).isEqualTo("http://backend/video");
            assertThat(requestCaptor.getValue().requireReal()).isFalse();
            assertThat(response.model()).containsEntry("generationMode", "MOCK");
            assertThat(appender.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("Video LLM 분석 응답을 받았습니다")
                            .contains("generationMode=MOCK"));
        } finally {
            stageLogger.detachAppender(appender);
        }
    }

    @Test
    void durationProbeFailureUsesMaximumConservativePermitsAndNullDuration() {
        when(videoDurationProbe.probe(Path.of(VIDEO_PATH)))
                .thenThrow(new IllegalStateException("ffprobe unavailable"));
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenReturn(response("MOCK"));

        AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, true, VIDEO_PATH);
        assertThat(reserveBudgetOrSkip(plan)).isEmpty();
        stage.analyze(JOB_ID, VIDEO_PATH, "http://backend/video", plan);

        verify(userRateLimiter).reserveVideoLlmBudget(OWNER_ID, "2026-08", 18);
        ArgumentCaptor<VideoLlmEngineRequest> requestCaptor =
                ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        verify(videoLlmEngineClient).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue().durationSec()).isNull();
    }

    @Test
    void disabledReanalysisFailsWithRealRequiredWithoutProbeOrBudget() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);

        assertThatThrownBy(() -> stage.prepare(JOB_ID, false, VIDEO_PATH))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_REAL_REQUIRED));

        verifyNoInteractions(videoDurationProbe, userRateLimiter, videoLlmEngineClient);
    }

    @Test
    void budgetDeniedReanalysisFailsWithUsageLimitInsteadOfReturningSkippedData() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);
        when(userRateLimiter.reserveVideoLlmBudget(OWNER_ID, "2026-08", 18))
                .thenReturn(UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED);
        AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, true, VIDEO_PATH);

        assertThatThrownBy(() -> reserveBudgetOrSkip(plan))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VIDEO_LLM_USAGE_LIMIT_EXCEEDED));

        verifyNoInteractions(videoLlmEngineClient);
    }

    @Test
    void reanalysisRequestRequiresRealProviderResponse() {
        when(analysisJob.getAnalysisKind()).thenReturn(AnalysisKind.VIDEO_LLM_REANALYSIS);
        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenReturn(response("FALLBACK"));
        AnalysisVideoLlmStage.Plan plan = stage.prepare(JOB_ID, true, VIDEO_PATH);
        assertThat(plan.requireReal()).isTrue();
        assertThat(reserveBudgetOrSkip(plan)).isEmpty();

        assertThatThrownBy(() -> stage.analyze(JOB_ID, VIDEO_PATH, "http://backend/video", plan))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_REAL_REQUIRED));

        ArgumentCaptor<VideoLlmEngineRequest> requestCaptor =
                ArgumentCaptor.forClass(VideoLlmEngineRequest.class);
        verify(videoLlmEngineClient).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue().requireReal()).isTrue();
    }

    private Optional<VideoLlmEngineResponse> reserveBudgetOrSkip(AnalysisVideoLlmStage.Plan plan) {
        return stage.reserveBudgetOrSkip(JOB_ID, plan, 100.0, 30);
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
