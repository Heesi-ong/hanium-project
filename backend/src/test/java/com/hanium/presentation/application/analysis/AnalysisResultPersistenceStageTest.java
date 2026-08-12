package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisResultPersistenceStageTest {

    private static final String JOB_ID = "result-stage-job";

    private ResultCommandService resultCommandService;
    private AnalysisResultPersistenceStage stage;

    @BeforeEach
    void setUp() {
        resultCommandService = mock(ResultCommandService.class);
        stage = new AnalysisResultPersistenceStage(resultCommandService);
    }

    @Test
    void compactDelegatesBothEngineResponsesAndReturnsStoredCompactResult() {
        AnalysisEngineResponse basic = mock(AnalysisEngineResponse.class);
        VideoLlmEngineResponse videoLlm = mock(VideoLlmEngineResponse.class);
        Map<String, Object> compact = Map.of("score", 82);
        when(resultCommandService.saveEngineResultsAndCompact(JOB_ID, basic, videoLlm))
                .thenReturn(compact);

        Map<String, Object> result = stage.compact(JOB_ID, basic, videoLlm);

        assertThat(result).isSameAs(compact);
    }

    @Test
    void saveFinalDelegatesAllPipelineResponses() {
        AnalysisEngineResponse basic = mock(AnalysisEngineResponse.class);
        VideoLlmEngineResponse videoLlm = mock(VideoLlmEngineResponse.class);
        OpenAiFeedbackResponse feedback = mock(OpenAiFeedbackResponse.class);

        stage.saveFinal(JOB_ID, basic, videoLlm, feedback);

        verify(resultCommandService).saveFinalResult(JOB_ID, basic, videoLlm, feedback);
    }

    @Test
    void saveFailureUsesFailedTerminalContract() {
        stage.saveFailureSafely(JOB_ID, "엔진 연결 실패");

        verify(resultCommandService).saveFailureResult(JOB_ID, "FAILED", "엔진 연결 실패");
    }

    @Test
    void saveCancelledUsesCancelledTerminalContract() {
        stage.saveCancelledSafely(JOB_ID);

        verify(resultCommandService).saveFailureResult(
                JOB_ID,
                "CANCELLED",
                "사용자 요청으로 분석 작업이 취소되었습니다."
        );
    }

    @Test
    void terminalResultStorageFailureDoesNotReplaceOriginalPipelineOutcome() {
        doThrow(new RuntimeException("storage unavailable"))
                .when(resultCommandService)
                .saveFailureResult(JOB_ID, "FAILED", "원래 분석 실패");

        assertThatCode(() -> stage.saveFailureSafely(JOB_ID, "원래 분석 실패"))
                .doesNotThrowAnyException();
    }
}
