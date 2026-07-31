package com.hanium.presentation.application.result;

import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

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

    @Test
    void compactIncludesVolumeStabilityInSpeechSummaryForOpenAiInput() {
        Map<String, Object> compactResult = analysisCompactor.compact(
                "job-1",
                analysisResponse(),
                videoLlmResponse("REAL")
        );

        assertThat(speechSummary(compactResult))
                .containsEntry("volumeStabilityScore", 64)
                .containsEntry("volumeStabilityImplemented", true)
                .containsEntry("volumeStabilityFallbackReason", "")
                .containsEntry("volumeRmsDbStdDev", 8.25)
                .containsEntry("volumeAnalyzedWindowCount", 18)
                .containsEntry("volumeSilentWindowCount", 2);
    }

    @Test
    void compactIncludesRuleBasedContentStructureForOpenAiInput() {
        Map<String, Object> compactResult = analysisCompactor.compact(
                "job-1",
                analysisResponse(),
                videoLlmResponse("REAL")
        );

        assertThat(dataPolicy(compactResult))
                .containsEntry("contentAnalysis", "STT transcript 기반 발표 내용 구조 지표를 룰 기반으로 요약합니다.");
        assertThat(contentStructure(compactResult))
                .containsEntry("available", true)
                .containsEntry("sentenceCount", 3)
                .containsEntry("wordCount", 10)
                .containsEntry("averageWordsPerSentence", 3.3)
                .containsEntry("questionSentenceCount", 1)
                .containsEntry("transitionMarkerCount", 2)
                .containsEntry("structureHint", "partially_structured");
        assertThat(transcriptSummary(compactResult).get("note").toString())
                .doesNotContain("아직 수행하지 않습니다");
    }

    private Map<String, Object> dataPolicy(Map<String, Object> compactResult) {
        return JsonMapSupport.copyStringKeyedMap(compactResult.get("dataPolicy"));
    }

    private Map<String, Object> instructionHints(Map<String, Object> compactResult) {
        return JsonMapSupport.copyStringKeyedMap(compactResult.get("llmInstructionHints"));
    }

    private Map<String, Object> speechSummary(Map<String, Object> compactResult) {
        Map<String, Object> modelInputs = JsonMapSupport.copyStringKeyedMap(
                compactResult.get("modelInputs")
        );
        return JsonMapSupport.copyStringKeyedMap(modelInputs.get("speechSummary"));
    }

    private Map<String, Object> transcriptSummary(Map<String, Object> compactResult) {
        Map<String, Object> modelInputs = JsonMapSupport.copyStringKeyedMap(
                compactResult.get("modelInputs")
        );
        return JsonMapSupport.copyStringKeyedMap(modelInputs.get("transcriptSummary"));
    }

    private Map<String, Object> contentStructure(Map<String, Object> compactResult) {
        return JsonMapSupport.copyStringKeyedMap(
                transcriptSummary(compactResult).get("contentStructure")
        );
    }

    private AnalysisEngineResponse analysisResponse() {
        return new AnalysisEngineResponse(
                "job-1",
                "completed",
                Map.of(),
                Map.of(),
                Map.ofEntries(
                        entry("speechScore", 72),
                        entry("speechSpeedWpm", 128),
                        entry("speechSpeedScore", 100),
                        entry("silenceCount", 1),
                        entry("totalSilenceTime", 2.4),
                        entry("silenceRatio", 0.04),
                        entry("silenceScore", 100),
                        entry("volumeStabilityScore", 64),
                        entry("volumeStabilityImplemented", true),
                        entry("volumeStabilityFallbackReason", ""),
                        entry("volumeRmsDbStdDev", 8.25),
                        entry("volumeAnalyzedWindowCount", 18),
                        entry("volumeSilentWindowCount", 2),
                        entry("estimatedWordCount", 130),
                        entry("stt", Map.of(
                                "success", true,
                                "language", "ko",
                                "languageProbability", 0.98,
                                "segmentCount", 3,
                                "wordCount", 10,
                                "transcript", "먼저 문제를 설명합니다. 다음으로 해결 방법을 제안합니다. 왜 정말 중요할까요?"
                        ))
                ),
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
