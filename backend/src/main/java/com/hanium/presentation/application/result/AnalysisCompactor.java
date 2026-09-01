package com.hanium.presentation.application.result;

import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AnalysisCompactor {

    public Map<String, Object> compact(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> compactResult = new LinkedHashMap<>();

        compactResult.put("jobId", jobId);
        compactResult.put("createdAt", LocalDateTime.now().toString());
        compactResult.put("compactPurpose", "LLM 피드백 생성을 위한 분석 데이터 정리");
        compactResult.put("dataPolicy", createDataPolicy(videoLlmEngineResponse));
        compactResult.put("scoringPolicy", createScoringPolicy());
        compactResult.put("rawMetrics", createRawMetrics(analysisEngineResponse));
        compactResult.put("modelInputs", createModelInputs(
                analysisEngineResponse,
                videoLlmEngineResponse
        ));
        compactResult.put("llmInstructionHints", createLlmInstructionHints(videoLlmEngineResponse));

        return compactResult;
    }

    private Map<String, Object> createDataPolicy(
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> dataPolicy = new LinkedHashMap<>();
        String videoLlmGenerationMode = resolveVideoLlmGenerationMode(videoLlmEngineResponse);

        dataPolicy.put("rawMetrics", "analysis-engine에서 생성한 원시 분석 지표입니다.");
        dataPolicy.put("modelInputs", "LLM 또는 Mock 피드백 생성기가 우선 참고할 요약 입력입니다.");
        dataPolicy.put("ruleBasedInterpretation", "현재 단계에서는 생성하지 않습니다.");
        dataPolicy.put("contentAnalysis", "STT transcript 기반 발표 내용 구조 지표를 룰 기반으로 요약합니다.");
        dataPolicy.put("videoLlmAnalysis", resolveVideoLlmDataPolicy(videoLlmGenerationMode));
        dataPolicy.put("videoLlmGenerationMode", videoLlmGenerationMode);
        dataPolicy.put("openAiFeedback", "OpenAI 피드백은 런타임 설정과 API 호출 결과에 따라 REAL, MOCK, FALLBACK 중 하나로 생성됩니다.");

        return dataPolicy;
    }

    private Map<String, Object> createScoringPolicy() {
        Map<String, Object> scoringPolicy = new LinkedHashMap<>();

        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("postureScore", 5.0 / 12.0);
        weights.put("speechScore", 5.0 / 12.0);
        weights.put("gestureScore", 1.0 / 6.0);

        scoringPolicy.put("formulaVersion", "weighted-v2");
        scoringPolicy.put("totalScoreFormula", "postureScore * 5/12 + speechScore * 5/12 + gestureScore * 1/6");
        scoringPolicy.put("weights", weights);
        scoringPolicy.put("scoreRange", "0-100");
        scoringPolicy.put("source", "시선·표정 제외 후 기존 자세/음성/제스처 상대 비중 정규화");
        scoringPolicy.put("note", "시선 및 표정 검출 결과는 사용자 점수와 피드백 생성에 사용하지 않습니다.");

        return scoringPolicy;
    }

    private Map<String, Object> createRawMetrics(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> rawMetrics = new LinkedHashMap<>();

        rawMetrics.put("videoInfo", nullSafeMap(analysisEngineResponse.videoInfo()));
        rawMetrics.put("frame", nullSafeMap(analysisEngineResponse.frame()));
        rawMetrics.put("audio", nullSafeMap(analysisEngineResponse.audio()));
        rawMetrics.put("filler", nullSafeMap(analysisEngineResponse.filler()));
        rawMetrics.put("pose", nullSafeMap(analysisEngineResponse.pose()));
        rawMetrics.put("gesture", nullSafeMap(analysisEngineResponse.gesture()));
        Map<String, Object> score = nullSafeMap(analysisEngineResponse.score());
        Map<String, Object> supportedScore = new LinkedHashMap<>();
        supportedScore.put("totalScore", getOrDefault(score, "totalScore", 0));
        supportedScore.put("rawScore", getOrDefault(score, "rawScore", 0));
        supportedScore.put("penalty", getOrDefault(score, "penalty", 0));
        supportedScore.put("postureScore", getOrDefault(score, "postureScore", 0));
        supportedScore.put("speechScore", getOrDefault(score, "speechScore", 0));
        supportedScore.put("gestureScore", getOrDefault(score, "gestureScore", 0));
        supportedScore.put("reliability", getOrDefault(score, "reliability", Map.of()));
        supportedScore.put("explanation", getOrDefault(score, "explanation", Map.of()));
        rawMetrics.put("score", supportedScore);

        return rawMetrics;
    }

    private Map<String, Object> createModelInputs(
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> modelInputs = new LinkedHashMap<>();

        modelInputs.put("scoreSummary", createScoreSummary(analysisEngineResponse));
        modelInputs.put("speechSummary", createSpeechSummary(analysisEngineResponse));
        modelInputs.put("visualSummary", createVisualSummary(
                analysisEngineResponse,
                videoLlmEngineResponse
        ));
        modelInputs.put("transcriptSummary", createTranscriptSummary(analysisEngineResponse));
        modelInputs.put("feedbackFocus", createFeedbackFocus(analysisEngineResponse));

        return modelInputs;
    }

    private Map<String, Object> createScoreSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> score = nullSafeMap(analysisEngineResponse.score());

        Map<String, Object> scoreSummary = new LinkedHashMap<>();

        scoreSummary.put("totalScore", getOrDefault(score, "totalScore", 0));
        scoreSummary.put("postureScore", getOrDefault(score, "postureScore", 0));
        scoreSummary.put("speechScore", getOrDefault(score, "speechScore", 0));
        scoreSummary.put("gestureScore", getOrDefault(score, "gestureScore", 0));
        scoreSummary.put("level", resolveLevel(getNumberValue(score, "totalScore")));

        return scoreSummary;
    }

    private Map<String, Object> createSpeechSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> audio = nullSafeMap(analysisEngineResponse.audio());
        Map<String, Object> filler = nullSafeMap(analysisEngineResponse.filler());

        Map<String, Object> speechSummary = new LinkedHashMap<>();

        speechSummary.put("analysisMethod", getOrDefault(audio, "analysisMethod", "-"));
        speechSummary.put("speechScore", getOrDefault(audio, "speechScore", 0));
        speechSummary.put("speechSpeedWpm", getOrDefault(audio, "speechSpeedWpm", 0));
        speechSummary.put("speechSpeedScore", getOrDefault(audio, "speechSpeedScore", 0));
        speechSummary.put("silenceCount", getOrDefault(audio, "silenceCount", 0));
        speechSummary.put("totalSilenceTime", getOrDefault(audio, "totalSilenceTime", 0));
        speechSummary.put("silenceRatio", getOrDefault(audio, "silenceRatio", 0));
        speechSummary.put("silenceScore", getOrDefault(audio, "silenceScore", 0));
        speechSummary.put("volumeStabilityScore", getOrDefault(audio, "volumeStabilityScore", 0));
        speechSummary.put("volumeStabilityImplemented", getOrDefault(audio, "volumeStabilityImplemented", false));
        speechSummary.put("volumeStabilityFallbackReason", getOrDefault(audio, "volumeStabilityFallbackReason", ""));
        speechSummary.put("volumeRmsDbStdDev", getOrDefault(audio, "volumeRmsDbStdDev", null));
        speechSummary.put("volumeAnalyzedWindowCount", getOrDefault(audio, "volumeAnalyzedWindowCount", 0));
        speechSummary.put("volumeSilentWindowCount", getOrDefault(audio, "volumeSilentWindowCount", 0));
        speechSummary.put("estimatedWordCount", getOrDefault(audio, "estimatedWordCount", 0));
        speechSummary.put("fillerScore", getOrDefault(filler, "fillerScore", 0));
        speechSummary.put("fillerCount", getOrDefault(filler, "fillerCount", 0));
        speechSummary.put("fillerRatio", getOrDefault(filler, "fillerRatio", 0));
        speechSummary.put("fillerWords", getOrDefault(filler, "fillerWords", java.util.List.of()));

        return speechSummary;
    }

    private Map<String, Object> createVisualSummary(
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> pose = nullSafeMap(analysisEngineResponse.pose());
        Map<String, Object> gesture = nullSafeMap(analysisEngineResponse.gesture());

        Map<String, Object> visualSummary = new LinkedHashMap<>();

        visualSummary.put("postureScore", getOrDefault(pose, "postureScore", 0));
        visualSummary.put("poseDetectionRate", getOrDefault(pose, "detectionRate", 0));
        visualSummary.put("shoulderBalanceScore", getOrDefault(pose, "shoulderBalanceScore", 0));
        visualSummary.put("averageShoulderDiff", getOrDefault(pose, "averageShoulderDiff", 0));

        visualSummary.put("gestureScore", getOrDefault(gesture, "gestureScore", 0));
        visualSummary.put("gestureRate", getOrDefault(gesture, "gestureRate", 0));
        visualSummary.put("handVisibilityRate", getOrDefault(gesture, "handVisibilityRate", 0));
        visualSummary.put("averageWristMovement", getOrDefault(gesture, "averageWristMovement", 0));

        visualSummary.put("videoLlmModel", nullSafeMap(videoLlmEngineResponse.model()));
        Map<String, Object> supportedObservations = new LinkedHashMap<>();
        Map<String, java.util.List<Map<String, Object>>> observations = videoLlmEngineResponse.observations();
        if (observations != null) {
            supportedObservations.put("gesture", observations.getOrDefault("gesture", java.util.List.of()));
            supportedObservations.put("posture", observations.getOrDefault("posture", java.util.List.of()));
        }
        visualSummary.put("videoLlmObservations", supportedObservations);
        return visualSummary;
    }

    private Map<String, Object> createTranscriptSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> audio = nullSafeMap(analysisEngineResponse.audio());
        Map<String, Object> stt = nullSafeMap(audio.get("stt"));

        Map<String, Object> transcriptSummary = new LinkedHashMap<>();

        transcriptSummary.put("sttSuccess", getOrDefault(stt, "success", false));
        transcriptSummary.put("language", getOrDefault(stt, "language", "unknown"));
        transcriptSummary.put("languageProbability", getOrDefault(stt, "languageProbability", 0));
        transcriptSummary.put("segmentCount", getOrDefault(stt, "segmentCount", 0));
        transcriptSummary.put("wordCount", getOrDefault(stt, "wordCount", 0));
        transcriptSummary.put("transcript", getOrDefault(stt, "transcript", ""));
        transcriptSummary.put("contentStructure", createContentStructureSummary(
                getOrDefault(stt, "transcript", "").toString()
        ));
        transcriptSummary.put("note", "STT transcript와 룰 기반 발표 내용 구조 지표를 함께 제공합니다. 주제 적합성이나 사실성 판단은 LLM 피드백 단계에서만 다룹니다.");

        return transcriptSummary;
    }

    private Map<String, Object> createContentStructureSummary(String transcript) {
        String normalizedTranscript = normalizeTranscript(transcript);
        java.util.List<String> sentences = splitSentences(normalizedTranscript);
        java.util.List<String> words = splitWords(normalizedTranscript);

        int sentenceCount = sentences.size();
        int wordCount = words.size();
        int questionSentenceCount = countQuestionSentences(transcript);
        int transitionMarkerCount = countTransitionMarkers(words);

        Map<String, Object> contentStructure = new LinkedHashMap<>();
        contentStructure.put("available", !normalizedTranscript.isBlank());
        contentStructure.put("sentenceCount", sentenceCount);
        contentStructure.put("wordCount", wordCount);
        contentStructure.put("averageWordsPerSentence", sentenceCount == 0
                ? 0
                : Math.round((wordCount * 10.0) / sentenceCount) / 10.0);
        contentStructure.put("questionSentenceCount", questionSentenceCount);
        contentStructure.put("transitionMarkerCount", transitionMarkerCount);
        contentStructure.put("structureHint", resolveStructureHint(sentenceCount, transitionMarkerCount));

        return contentStructure;
    }

    private String normalizeTranscript(String transcript) {
        if (transcript == null) {
            return "";
        }

        return transcript.trim().replaceAll("\\s+", " ");
    }

    private java.util.List<String> splitSentences(String transcript) {
        if (transcript.isBlank()) {
            return java.util.List.of();
        }

        return java.util.Arrays.stream(transcript.split("[.!?。？！]+|\\n+"))
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .toList();
    }

    private java.util.List<String> splitWords(String transcript) {
        if (transcript.isBlank()) {
            return java.util.List.of();
        }

        return java.util.Arrays.stream(transcript.split("\\s+"))
                .map(String::trim)
                .filter(word -> !word.isBlank())
                .toList();
    }

    private int countQuestionSentences(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return 0;
        }

        int count = 0;
        for (char character : transcript.toCharArray()) {
            if (character == '?' || character == '？') {
                count++;
            }
        }
        return count;
    }

    private int countTransitionMarkers(java.util.List<String> words) {
        java.util.Set<String> transitionMarkers = java.util.Set.of(
                "첫째", "둘째", "셋째", "마지막으로", "결론적으로", "따라서", "그러므로",
                "또한", "반면", "하지만", "그리고", "먼저", "다음으로", "요약하면"
        );

        int count = 0;
        for (String word : words) {
            String normalizedWord = word.replaceAll("[,.;:!?。？！]", "");
            if (transitionMarkers.contains(normalizedWord)) {
                count++;
            }
        }
        return count;
    }

    private String resolveStructureHint(int sentenceCount, int transitionMarkerCount) {
        if (sentenceCount == 0) {
            return "transcript_unavailable";
        }

        if (transitionMarkerCount >= 3) {
            return "structured";
        }

        if (sentenceCount >= 3 && transitionMarkerCount >= 1) {
            return "partially_structured";
        }

        return "needs_structure_markers";
    }

    private Map<String, Object> createFeedbackFocus(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> score = nullSafeMap(analysisEngineResponse.score());

        int postureScore = getNumberValue(score, "postureScore");
        int speechScore = getNumberValue(score, "speechScore");
        int gestureScore = getNumberValue(score, "gestureScore");

        Map<String, Object> feedbackFocus = new LinkedHashMap<>();

        feedbackFocus.put("strongestArea", resolveStrongestArea(
                postureScore,
                speechScore,
                gestureScore
        ));
        feedbackFocus.put("weakestArea", resolveWeakestArea(
                postureScore,
                speechScore,
                gestureScore
        ));
        feedbackFocus.put("priority", createPriority(
                postureScore,
                speechScore,
                gestureScore
        ));

        return feedbackFocus;
    }

    private Map<String, Object> createLlmInstructionHints(
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> hints = new LinkedHashMap<>();
        String videoLlmGenerationMode = resolveVideoLlmGenerationMode(videoLlmEngineResponse);

        hints.put("doNotInvent", "제공된 수치에 없는 내용을 임의로 생성하지 않습니다.");
        hints.put("useRawMetricsFirst", "rawMetrics는 원본 수치 확인용으로 사용합니다.");
        hints.put("useModelInputsFirst", "modelInputs는 피드백 생성 시 우선 참고합니다.");
        hints.put("separateObservationAndJudgement", "검출률, 점수, 비율 같은 관찰값과 개선 조언을 구분합니다.");
        hints.put("avoidOverclaiming", "MediaPipe 기반 추정값은 확정 진단처럼 표현하지 않습니다.");
        hints.put("videoLlmGenerationMode", videoLlmGenerationMode);
        hints.put("currentLimitation", resolveCurrentLimitation(videoLlmGenerationMode));

        return hints;
    }

    private String resolveVideoLlmGenerationMode(
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> model = nullSafeMap(videoLlmEngineResponse.model());
        Object generationMode = model.get("generationMode");

        if (generationMode == null || generationMode.toString().isBlank()) {
            return "UNKNOWN";
        }

        return generationMode.toString();
    }

    private String resolveVideoLlmDataPolicy(String generationMode) {
        if ("REAL".equals(generationMode)) {
            return "video-llm-engine 실제 모델 결과입니다.";
        }

        if ("FALLBACK".equals(generationMode)) {
            return "video-llm-engine 실제 모델 호출 실패 후 Mock 결과로 대체되었습니다.";
        }

        if ("MOCK".equals(generationMode)) {
            return "video-llm-engine Mock 결과입니다.";
        }

        if ("SKIPPED".equals(generationMode)) {
            return "Video LLM 분석이 정책 또는 사용자 선택에 의해 생략되었습니다.";
        }

        return "Video LLM 결과 생성 방식을 확인할 수 없습니다.";
    }

    private String resolveCurrentLimitation(String videoLlmGenerationMode) {
        if ("REAL".equals(videoLlmGenerationMode)) {
            return "Video LLM은 실제 모델 결과이며, OpenAI 피드백은 런타임 설정과 API 호출 결과에 따라 생성됩니다.";
        }

        if ("FALLBACK".equals(videoLlmGenerationMode) || "MOCK".equals(videoLlmGenerationMode)) {
            return "Video LLM 시각 분석은 실제 영상 분석 결과가 아닌 예시/대체 데이터일 수 있으며, OpenAI 피드백은 런타임 설정과 API 호출 결과에 따라 생성됩니다.";
        }

        if ("SKIPPED".equals(videoLlmGenerationMode)) {
            return "Video LLM 시각 분석은 생략되었으며, OpenAI 피드백은 기본 분석 결과 중심으로 생성됩니다.";
        }

        return "Video LLM 결과 생성 방식을 확인할 수 없으며, OpenAI 피드백은 런타임 설정과 API 호출 결과에 따라 생성됩니다.";
    }

    private java.util.List<String> createPriority(
            int postureScore,
            int speechScore,
            int gestureScore
    ) {
        java.util.List<String> priority = new java.util.ArrayList<>();

        if (speechScore < 70) {
            priority.add("speech");
        }

        if (postureScore < 70) {
            priority.add("posture");
        }

        if (gestureScore < 70) {
            priority.add("gesture");
        }

        if (priority.isEmpty()) {
            priority.add("content_structure");
        }

        return priority;
    }

    private String resolveLevel(int totalScore) {
        if (totalScore >= 85) {
            return "EXCELLENT";
        }

        if (totalScore >= 70) {
            return "GOOD";
        }

        if (totalScore >= 50) {
            return "NORMAL";
        }

        return "NEEDS_IMPROVEMENT";
    }

    private String resolveStrongestArea(
            int postureScore,
            int speechScore,
            int gestureScore
    ) {
        int maxScore = postureScore;
        String area = "posture";

        if (speechScore > maxScore) {
            maxScore = speechScore;
            area = "speech";
        }

        if (gestureScore > maxScore) {
            maxScore = gestureScore;
            area = "gesture";
        }

        return area;
    }

    private String resolveWeakestArea(
            int postureScore,
            int speechScore,
            int gestureScore
    ) {
        int minScore = postureScore;
        String area = "posture";

        if (speechScore < minScore) {
            minScore = speechScore;
            area = "speech";
        }

        if (gestureScore < minScore) {
            minScore = gestureScore;
            area = "gesture";
        }

        return area;
    }

    private Map<String, Object> nullSafeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private Map<String, Object> nullSafeMap(Object value) {
        return JsonMapSupport.copyStringKeyedMap(value);
    }

    private Object nullSafeObject(Object value) {
        return value == null ? Map.of() : value;
    }

    private Object getOrDefault(
            Map<String, Object> map,
            String key,
            Object defaultValue
    ) {
        Object value = map.get(key);
        return value == null ? defaultValue : value;
    }

    private int getNumberValue(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        return 0;
    }
}
