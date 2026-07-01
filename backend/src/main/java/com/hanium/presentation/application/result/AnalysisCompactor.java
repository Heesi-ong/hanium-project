package com.hanium.presentation.application.result;

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
        compactResult.put("dataPolicy", createDataPolicy());
        compactResult.put("scoringPolicy", createScoringPolicy());
        compactResult.put("rawMetrics", createRawMetrics(analysisEngineResponse));
        compactResult.put("modelInputs", createModelInputs(
                analysisEngineResponse,
                videoLlmEngineResponse
        ));
        compactResult.put("llmInstructionHints", createLlmInstructionHints());

        return compactResult;
    }

    private Map<String, Object> createDataPolicy() {
        Map<String, Object> dataPolicy = new LinkedHashMap<>();

        dataPolicy.put("rawMetrics", "analysis-engine에서 생성한 원시 분석 지표입니다.");
        dataPolicy.put("modelInputs", "LLM 또는 Mock 피드백 생성기가 우선 참고할 요약 입력입니다.");
        dataPolicy.put("ruleBasedInterpretation", "현재 단계에서는 생성하지 않습니다.");
        dataPolicy.put("contentAnalysis", "STT transcript 기반 내용 분석은 아직 수행하지 않습니다.");
        dataPolicy.put("videoLlmAnalysis", "현재는 video-llm-engine Mock 결과입니다.");
        dataPolicy.put("openAiFeedback", "현재는 실제 OpenAI 호출 없이 Mock 피드백을 생성합니다.");

        return dataPolicy;
    }

    private Map<String, Object> createScoringPolicy() {
        Map<String, Object> scoringPolicy = new LinkedHashMap<>();

        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("postureScore", 0.20);
        weights.put("gazeScore", 0.20);
        weights.put("speechScore", 0.30);
        weights.put("gestureScore", 0.15);
        weights.put("emotionScore", 0.15);

        scoringPolicy.put("totalScoreFormula", "postureScore * 0.20 + gazeScore * 0.20 + speechScore * 0.30 + gestureScore * 0.15 + emotionScore * 0.15");
        scoringPolicy.put("weights", weights);
        scoringPolicy.put("scoreRange", "0-100");
        scoringPolicy.put("note", "점수 기준 완화 및 보정은 추후 별도 단계에서 진행합니다.");

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
        rawMetrics.put("face", nullSafeMap(analysisEngineResponse.face()));
        rawMetrics.put("emotion", nullSafeMap(analysisEngineResponse.emotion()));
        rawMetrics.put("score", nullSafeMap(analysisEngineResponse.score()));

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
        scoreSummary.put("gazeScore", getOrDefault(score, "gazeScore", 0));
        scoreSummary.put("speechScore", getOrDefault(score, "speechScore", 0));
        scoreSummary.put("gestureScore", getOrDefault(score, "gestureScore", 0));
        scoreSummary.put("emotionScore", getOrDefault(score, "emotionScore", 0));
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
        Map<String, Object> face = nullSafeMap(analysisEngineResponse.face());
        Map<String, Object> emotion = nullSafeMap(analysisEngineResponse.emotion());

        Map<String, Object> visualSummary = new LinkedHashMap<>();

        visualSummary.put("postureScore", getOrDefault(pose, "postureScore", 0));
        visualSummary.put("poseDetectionRate", getOrDefault(pose, "detectionRate", 0));
        visualSummary.put("shoulderBalanceScore", getOrDefault(pose, "shoulderBalanceScore", 0));
        visualSummary.put("averageShoulderDiff", getOrDefault(pose, "averageShoulderDiff", 0));

        visualSummary.put("gazeScore", getOrDefault(face, "gazeScore", 0));
        visualSummary.put("faceDetectionRate", getOrDefault(face, "detectionRate", 0));
        visualSummary.put("eyeContactLevel", getOrDefault(face, "eyeContactLevel", "unknown"));
        visualSummary.put("averageNoseOffset", getOrDefault(face, "averageNoseOffset", 0));

        visualSummary.put("gestureScore", getOrDefault(gesture, "gestureScore", 0));
        visualSummary.put("gestureRate", getOrDefault(gesture, "gestureRate", 0));
        visualSummary.put("handVisibilityRate", getOrDefault(gesture, "handVisibilityRate", 0));
        visualSummary.put("averageWristMovement", getOrDefault(gesture, "averageWristMovement", 0));

        visualSummary.put("emotionScore", getOrDefault(emotion, "emotionScore", 0));
        visualSummary.put("dominantEmotion", getOrDefault(emotion, "dominantEmotion", "unknown"));
        visualSummary.put("emotionCounts", getOrDefault(emotion, "emotionCounts", Map.of()));
        visualSummary.put("expressionScore", getOrDefault(emotion, "expressionScore", 0));
        visualSummary.put("expressionVarietyScore", getOrDefault(emotion, "expressionVarietyScore", 0));

        visualSummary.put("videoLlmModel", nullSafeMap(videoLlmEngineResponse.model()));
        visualSummary.put("videoLlmObservations", nullSafeObject(videoLlmEngineResponse.observations()));
        visualSummary.put("videoLlmGlobalSummary", nullSafeMap(videoLlmEngineResponse.globalSummary()));

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
        transcriptSummary.put("note", "현재 transcript는 LLM 입력용으로만 전달하며, 룰 기반 발표 내용 분석은 아직 수행하지 않습니다.");

        return transcriptSummary;
    }

    private Map<String, Object> createFeedbackFocus(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> score = nullSafeMap(analysisEngineResponse.score());

        int postureScore = getNumberValue(score, "postureScore");
        int gazeScore = getNumberValue(score, "gazeScore");
        int speechScore = getNumberValue(score, "speechScore");
        int gestureScore = getNumberValue(score, "gestureScore");
        int emotionScore = getNumberValue(score, "emotionScore");

        Map<String, Object> feedbackFocus = new LinkedHashMap<>();

        feedbackFocus.put("strongestArea", resolveStrongestArea(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore
        ));
        feedbackFocus.put("weakestArea", resolveWeakestArea(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore
        ));
        feedbackFocus.put("priority", createPriority(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore
        ));

        return feedbackFocus;
    }

    private Map<String, Object> createLlmInstructionHints() {
        Map<String, Object> hints = new LinkedHashMap<>();

        hints.put("doNotInvent", "제공된 수치에 없는 내용을 임의로 생성하지 않습니다.");
        hints.put("useRawMetricsFirst", "rawMetrics는 원본 수치 확인용으로 사용합니다.");
        hints.put("useModelInputsFirst", "modelInputs는 피드백 생성 시 우선 참고합니다.");
        hints.put("separateObservationAndJudgement", "검출률, 점수, 비율 같은 관찰값과 개선 조언을 구분합니다.");
        hints.put("avoidOverclaiming", "MediaPipe 기반 추정값은 확정 진단처럼 표현하지 않습니다.");
        hints.put("currentLimitation", "현재 Video LLM과 OpenAI는 Mock 상태입니다.");

        return hints;
    }

    private java.util.List<String> createPriority(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore
    ) {
        java.util.List<String> priority = new java.util.ArrayList<>();

        if (speechScore < 70) {
            priority.add("speech");
        }

        if (postureScore < 70) {
            priority.add("posture");
        }

        if (gazeScore < 70) {
            priority.add("gaze");
        }

        if (gestureScore < 70) {
            priority.add("gesture");
        }

        if (emotionScore < 70) {
            priority.add("emotion");
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
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore
    ) {
        int maxScore = postureScore;
        String area = "posture";

        if (gazeScore > maxScore) {
            maxScore = gazeScore;
            area = "gaze";
        }

        if (speechScore > maxScore) {
            maxScore = speechScore;
            area = "speech";
        }

        if (gestureScore > maxScore) {
            maxScore = gestureScore;
            area = "gesture";
        }

        if (emotionScore > maxScore) {
            area = "emotion";
        }

        return area;
    }

    private String resolveWeakestArea(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore
    ) {
        int minScore = postureScore;
        String area = "posture";

        if (gazeScore < minScore) {
            minScore = gazeScore;
            area = "gaze";
        }

        if (speechScore < minScore) {
            minScore = speechScore;
            area = "speech";
        }

        if (gestureScore < minScore) {
            minScore = gestureScore;
            area = "gesture";
        }

        if (emotionScore < minScore) {
            area = "emotion";
        }

        return area;
    }

    private Map<String, Object> nullSafeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nullSafeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return Map.of();
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