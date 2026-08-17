package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.CoachingProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisOpenAiFeedbackStageTest {

    private static final String JOB_ID = "job-openai-stage-test";

    private ResultCommandService resultCommandService;
    private OpenAiClient openAiClient;
    private AnalysisOpenAiFeedbackStage stage;

    @BeforeEach
    void setUp() {
        resultCommandService = mock(ResultCommandService.class);
        openAiClient = mock(OpenAiClient.class);
        stage = new AnalysisOpenAiFeedbackStage(resultCommandService, openAiClient);
    }

    @Test
    void disabledFeedbackIsStoredAsExplicitlySkippedWithoutCallingProvider() {
        OpenAiFeedbackResponse response = stage.generateAndSave(JOB_ID, false, Map.of("score", 80));

        assertThat(response.generationMode()).isEqualTo("SKIPPED");
        assertThat(response.realApiUsed()).isFalse();
        assertThat(response.model()).isNull();
        assertThat(response.fallbackReason())
                .isEqualTo("사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다.");
        assertThat(response.overallFeedback())
                .isEqualTo("OpenAI 피드백 생성을 사용하지 않았습니다. 기본 분석 결과만 저장되었습니다.");
        verify(resultCommandService, never()).loadExistingRealOpenAiFeedback(JOB_ID);
        verify(openAiClient, never()).generateFeedback(org.mockito.ArgumentMatchers.any());
        verify(resultCommandService).saveOpenAiFeedbackResult(JOB_ID, response);
    }

    @Test
    void existingRealFeedbackIsReusedAndPersistedWithoutProviderCall() {
        OpenAiFeedbackResponse existing = realFeedback("기존 실제 피드백");
        when(resultCommandService.loadExistingRealOpenAiFeedback(JOB_ID))
                .thenReturn(Optional.of(existing));

        OpenAiFeedbackResponse response = stage.generateAndSave(JOB_ID, true, Map.of("score", 80));

        assertThat(response).isSameAs(existing);
        verify(openAiClient, never()).generateFeedback(org.mockito.ArgumentMatchers.any());
        verify(resultCommandService).saveOpenAiFeedbackResult(JOB_ID, existing);
    }

    @Test
    void missingReusableFeedbackCallsProviderWithCompactAnalysisAndPersistsResponse() {
        Map<String, Object> compactAnalysis = Map.of("score", 80);
        OpenAiFeedbackResponse generated = realFeedback("새 실제 피드백");
        when(resultCommandService.loadExistingRealOpenAiFeedback(JOB_ID))
                .thenReturn(Optional.empty());
        when(openAiClient.generateFeedback(org.mockito.ArgumentMatchers.any()))
                .thenReturn(generated);

        OpenAiFeedbackResponse response = stage.generateAndSave(JOB_ID, true, compactAnalysis);

        ArgumentCaptor<OpenAiFeedbackRequest> requestCaptor =
                ArgumentCaptor.forClass(OpenAiFeedbackRequest.class);
        verify(openAiClient).generateFeedback(requestCaptor.capture());
        assertThat(requestCaptor.getValue().jobId()).isEqualTo(JOB_ID);
        assertThat(requestCaptor.getValue().compactAnalysis()).isSameAs(compactAnalysis);
        assertThat(response).isSameAs(generated);
        verify(resultCommandService).saveOpenAiFeedbackResult(JOB_ID, generated);
    }

    @Test
    void providerRequestIncludesOnlyNonIdentifyingCoachingProfile() {
        CoachingProfile profile = CoachingProfile.of("INTERVIEW", "BEGINNER", "GAZE");
        when(resultCommandService.loadExistingRealOpenAiFeedback(JOB_ID))
                .thenReturn(Optional.empty());
        when(openAiClient.generateFeedback(org.mockito.ArgumentMatchers.any()))
                .thenReturn(realFeedback("개인화 피드백"));

        stage.generateAndSave(JOB_ID, true, Map.of("score", 80), profile);

        ArgumentCaptor<OpenAiFeedbackRequest> requestCaptor =
                ArgumentCaptor.forClass(OpenAiFeedbackRequest.class);
        verify(openAiClient).generateFeedback(requestCaptor.capture());
        assertThat(requestCaptor.getValue().coachingProfile()).isEqualTo(profile);
    }

    private OpenAiFeedbackResponse realFeedback(String overallFeedback) {
        return OpenAiFeedbackResponse.real(
                JOB_ID,
                "gpt-test",
                overallFeedback,
                List.of("강점"),
                List.of("개선점"),
                List.of(),
                List.of()
        );
    }
}
