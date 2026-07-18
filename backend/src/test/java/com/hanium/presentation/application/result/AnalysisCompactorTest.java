package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCompactorTest {

    private final AnalysisCompactor analysisCompactor = new AnalysisCompactor();

    @Test
    void compactReflectsRealVideoLlmModeWithoutSayingVideoLlmIsMock() {
        Map<String, Object> compactResult = analysisCompactor.compact(
                "job-1",
                analysisResponse(),
                videoLlmResponse("REAL")
        );

        assertThat(dataPolicy(compactResult))
                .containsEntry("videoLlmGenerationMode", "REAL")
                .containsEntry("videoLlmAnalysis", "video-llm-engine 실제 모델 결과입니다.");
        assertThat(instructionHints(compactResult))
                .containsEntry("videoLlmGenerationMode", "REAL");
        assertThat(instructionHints(compactResult).get("currentLimitation").toString())
                .doesNotContain("Video LLM과 OpenAI는 Mock");
    }

    @Test
    void compactMarksFallbackVideoLlmAsReplacementDataForOpenAiInput() {
        Map<String, Object> compactResult = analysisCompactor.compact(
                "job-1",
                analysisResponse(),
                videoLlmResponse("FALLBACK")
        );

        assertThat(dataPolicy(compactResult))
                .containsEntry("videoLlmGenerationMode", "FALLBACK")
                .containsEntry("videoLlmAnalysis", "video-llm-engine 실제 모델 호출 실패 후 Mock 결과로 대체되었습니다.");
        assertThat(instructionHints(compactResult).get("currentLimitation").toString())
                .contains("예시/대체 데이터");
    }

    @Test
    void compactMarksSkippedVideoLlmAsSkippedForOpenAiInput() {
        Map<String, Object> compactResult = analysisCompactor.compact(
                "job-1",
                analysisResponse(),
                videoLlmResponse("SKIPPED")
        );

        assertThat(dataPolicy(compactResult))
                .containsEntry("videoLlmGenerationMode", "SKIPPED")
                .containsEntry("videoLlmAnalysis", "Video LLM 분석이 정책 또는 사용자 선택에 의해 생략되었습니다.");
        assertThat(instructionHints(compactResult).get("currentLimitation").toString())
                .contains("기본 분석 결과 중심");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataPolicy(Map<String, Object> compactResult) {
        return (Map<String, Object>) compactResult.get("dataPolicy");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> instructionHints(Map<String, Object> compactResult) {
        return (Map<String, Object>) compactResult.get("llmInstructionHints");
    }

    private AnalysisEngineResponse analysisResponse() {
        return new AnalysisEngineResponse(
                "job-1",
                "completed",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(
                        "totalScore", 80,
                        "postureScore", 80,
                        "gazeScore", 80,
                        "speechScore", 80,
                        "gestureScore", 80,
                        "expressionScore", 80
                ),
                Map.of()
        );
    }

    private VideoLlmEngineResponse videoLlmResponse(String generationMode) {
        return new VideoLlmEngineResponse(
                "job-1",
                "completed",
                Map.of("generationMode", generationMode),
                Map.of(),
                Map.of()
        );
    }
}
