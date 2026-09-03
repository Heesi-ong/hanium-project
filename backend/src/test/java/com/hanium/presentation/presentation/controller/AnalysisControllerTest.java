package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.analysis.AnalysisCommandService;
import com.hanium.presentation.application.analysis.AnalysisProgressService;
import com.hanium.presentation.application.analysis.AnalysisQueryService;
import com.hanium.presentation.application.analysis.BasicAnalysisStepReader;
import com.hanium.presentation.application.analysis.VideoLlmReanalysisService;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.request.AnalysisRunRequest;
import com.hanium.presentation.presentation.dto.request.VideoLlmReanalysisRequest;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import com.hanium.presentation.presentation.dto.response.VideoLlmReanalysisResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisControllerTest {

    private static final String JOB_ID = "20260903120000-abcdef01";
    private static final long OWNER_ID = 42L;

    private AnalysisCommandService analysisCommandService;
    private AnalysisQueryService analysisQueryService;
    private AnalysisProgressService analysisProgressService;
    private BasicAnalysisStepReader basicAnalysisStepReader;
    private VideoLlmReanalysisService videoLlmReanalysisService;
    private AnalysisController controller;

    @BeforeEach
    void setUp() {
        analysisCommandService = mock(AnalysisCommandService.class);
        analysisQueryService = mock(AnalysisQueryService.class);
        analysisProgressService = mock(AnalysisProgressService.class);
        basicAnalysisStepReader = mock(BasicAnalysisStepReader.class);
        videoLlmReanalysisService = mock(VideoLlmReanalysisService.class);
        controller = new AnalysisController(
                analysisCommandService,
                analysisQueryService,
                analysisProgressService,
                basicAnalysisStepReader,
                videoLlmReanalysisService
        );
    }

    private Authentication auth(Object details) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getDetails()).thenReturn(details);
        return authentication;
    }

    private AnalysisStatusResponse status(AnalysisStatus analysisStatus) {
        return new AnalysisStatusResponse(
                JOB_ID,
                analysisStatus,
                analysisStatus.getDescription(),
                null,
                LocalDateTime.of(2026, 9, 3, 12, 0),
                LocalDateTime.of(2026, 9, 3, 12, 1),
                null
        );
    }

    // --- /progress: Redis 캐시 유무에 따른 분기 ---

    @Test
    void progressReturnsRedisCacheVerbatimWhenPresent() {
        when(analysisQueryService.getStatus(JOB_ID, OWNER_ID))
                .thenReturn(status(AnalysisStatus.COMPACTING));
        Map<String, Object> cached = new LinkedHashMap<>(Map.of("percent", 63, "message", "정리 중"));
        when(analysisProgressService.getProgress(JOB_ID)).thenReturn(cached);

        Map<String, Object> progress = controller
                .getAnalysisProgress(JOB_ID, auth(OWNER_ID))
                .data();

        assertThat(progress).containsEntry("percent", 63).containsEntry("message", "정리 중");
        verify(basicAnalysisStepReader, never()).read(any());
    }

    @ParameterizedTest
    @CsvSource({
            "UPLOADED, 0",
            "QUEUED, 5",
            "BASIC_ANALYZING, 10",
            "VIDEO_LLM_ANALYZING, 40",
            "COMPACTING, 60",
            "OPENAI_GENERATING, 75",
            "MERGING_RESULT, 90",
            "COMPLETED, 100",
            "FAILED, 0",
            "CANCELLED, 0",
            "DEAD_LETTER, 0",
    })
    void progressFallsBackToDbStatusPercentWhenRedisCacheMissing(
            AnalysisStatus analysisStatus,
            int expectedPercent
    ) {
        when(analysisQueryService.getStatus(JOB_ID, OWNER_ID)).thenReturn(status(analysisStatus));
        when(analysisProgressService.getProgress(JOB_ID)).thenReturn(null);
        when(basicAnalysisStepReader.read(JOB_ID)).thenReturn(Optional.empty());

        Map<String, Object> progress = controller
                .getAnalysisProgress(JOB_ID, auth(OWNER_ID))
                .data();

        assertThat(progress).containsEntry("percent", expectedPercent);
        assertThat(progress.get("status")).isEqualTo(analysisStatus.name());
        assertThat(progress.get("message").toString()).contains("Redis 진행률 캐시가 없어");
    }

    @Test
    void progressEnrichesBasicAnalyzingWithEngineSubStep() {
        when(analysisQueryService.getStatus(JOB_ID, OWNER_ID))
                .thenReturn(status(AnalysisStatus.BASIC_ANALYZING));
        when(analysisProgressService.getProgress(JOB_ID)).thenReturn(null);
        when(basicAnalysisStepReader.read(JOB_ID)).thenReturn(Optional.of(
                new LinkedHashMap<>(Map.of("stepNo", 5, "totalSteps", 9, "label", "자세 분석"))
        ));

        Map<String, Object> progress = controller
                .getAnalysisProgress(JOB_ID, auth(OWNER_ID))
                .data();

        assertThat(progress).containsKey("basicAnalysisStep");
        assertThat(progress.get("message")).isEqualTo("자세 분석");
        // basicAnalysisPercent(5, 9) == 24 (10~38 구간)
        assertThat(progress.get("percent")).isEqualTo(24);
    }

    @Test
    void progressLeavesBasicAnalyzingUntouchedWhenNoEngineSubStepFile() {
        when(analysisQueryService.getStatus(JOB_ID, OWNER_ID))
                .thenReturn(status(AnalysisStatus.BASIC_ANALYZING));
        when(analysisProgressService.getProgress(JOB_ID)).thenReturn(null);
        when(basicAnalysisStepReader.read(JOB_ID)).thenReturn(Optional.empty());

        Map<String, Object> progress = controller
                .getAnalysisProgress(JOB_ID, auth(OWNER_ID))
                .data();

        assertThat(progress).doesNotContainKey("basicAnalysisStep");
        assertThat(progress).containsEntry("percent", 10);
    }

    // --- getCurrentUserId 분기 ---

    @Test
    void acceptsNumberAuthenticationDetails() {
        when(analysisQueryService.getStatus(eq(JOB_ID), eq(7L)))
                .thenReturn(status(AnalysisStatus.COMPLETED));

        ApiResponse<AnalysisStatusResponse> response =
                controller.getAnalysisStatus(JOB_ID, auth(Integer.valueOf(7)));

        assertThat(response.data().status()).isEqualTo(AnalysisStatus.COMPLETED);
    }

    @Test
    void rejectsAuthenticationDetailsThatCarryNoUserId() {
        assertThatThrownBy(() -> controller.getAnalysisStatus(JOB_ID, auth("not-a-user-id")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용자 id");
    }

    // --- run / retry 요청 본문 생략 시 기본값 ---

    @Test
    void runAnalysisUsesEnabledDefaultsWhenBodyOmitted() {
        when(analysisCommandService.runAnalysis(JOB_ID, OWNER_ID, true, true))
                .thenReturn(status(AnalysisStatus.QUEUED));

        controller.runAnalysis(JOB_ID, null, auth(OWNER_ID));

        verify(analysisCommandService).runAnalysis(JOB_ID, OWNER_ID, true, true);
    }

    @Test
    void retryAnalysisWithoutBodyPreservesStoredOptions() {
        when(analysisCommandService.retryAnalysis(JOB_ID, OWNER_ID))
                .thenReturn(status(AnalysisStatus.QUEUED));

        controller.retryAnalysis(JOB_ID, null, auth(OWNER_ID));

        verify(analysisCommandService).retryAnalysis(JOB_ID, OWNER_ID);
        verify(analysisCommandService, never())
                .retryAnalysis(any(), any(), any(), any());
    }

    @Test
    void retryAnalysisWithBodyPassesOverrides() {
        when(analysisCommandService.retryAnalysis(JOB_ID, OWNER_ID, false, true))
                .thenReturn(status(AnalysisStatus.QUEUED));

        controller.retryAnalysis(JOB_ID, new AnalysisRunRequest(false, true), auth(OWNER_ID));

        verify(analysisCommandService).retryAnalysis(JOB_ID, OWNER_ID, false, true);
    }

    // --- video-llm 재분석: reused 여부에 따른 HTTP 상태 ---

    @Test
    void videoLlmReanalysisReturns202WhenNewlyAccepted() {
        when(videoLlmReanalysisService.requestReanalysis(eq(JOB_ID), eq(OWNER_ID), any(), eq(true)))
                .thenReturn(new VideoLlmReanalysisResponse(JOB_ID, "child-1", AnalysisStatus.QUEUED, false));

        ResponseEntity<ApiResponse<VideoLlmReanalysisResponse>> response =
                controller.requestVideoLlmReanalysis(
                        JOB_ID, "idempotency-key-000001", null, auth(OWNER_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().data().reused()).isFalse();
    }

    @Test
    void videoLlmReanalysisReturns200WhenReusingExistingRequest() {
        when(videoLlmReanalysisService.requestReanalysis(eq(JOB_ID), eq(OWNER_ID), any(), eq(false)))
                .thenReturn(new VideoLlmReanalysisResponse(JOB_ID, "child-1", AnalysisStatus.COMPLETED, true));

        ResponseEntity<ApiResponse<VideoLlmReanalysisResponse>> response =
                controller.requestVideoLlmReanalysis(
                        JOB_ID,
                        "idempotency-key-000001",
                        new VideoLlmReanalysisRequest(false),
                        auth(OWNER_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().reused()).isTrue();
    }

    @Test
    void cancelAnalysisDelegatesToCommandService() {
        when(analysisCommandService.cancelAnalysis(JOB_ID, OWNER_ID))
                .thenReturn(status(AnalysisStatus.CANCELLED));

        ApiResponse<AnalysisStatusResponse> response =
                controller.cancelAnalysis(JOB_ID, auth(OWNER_ID));

        assertThat(response.data().status()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(analysisCommandService).cancelAnalysis(JOB_ID, OWNER_ID);
    }
}
