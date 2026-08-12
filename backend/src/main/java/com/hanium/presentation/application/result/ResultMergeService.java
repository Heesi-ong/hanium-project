package com.hanium.presentation.application.result;

import com.hanium.presentation.common.contract.ResultSchemaVersion;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.presentation.dto.response.ScoreSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResultMergeService {

    public Map<String, Object> createFinalResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Map<String, Object> finalResult = new LinkedHashMap<>();

        finalResult.put(ResultSchemaVersion.FIELD, ResultSchemaVersion.CURRENT);
        finalResult.put("jobId", jobId);
        finalResult.put("status", "COMPLETED");
        finalResult.put("createdAt", LocalDateTime.now().toString());
        finalResult.put("scoreSummary", createScoreSummary(analysisEngineResponse));
        finalResult.put("basicAnalysis", createBasicAnalysis(analysisEngineResponse));
        finalResult.put("visualAnalysis", createVisualAnalysis(videoLlmEngineResponse));
        finalResult.put("feedback", createFeedback(openAiFeedbackResponse));
        finalResult.put("practicePlan", nullSafeList(openAiFeedbackResponse.practicePlan()));
        finalResult.put("timelineFeedback", nullSafeList(openAiFeedbackResponse.timelineFeedback()));
        finalResult.put("notableMoments", createNotableMoments(analysisEngineResponse));
        finalResult.put("pipeline", createCompletedPipeline(videoLlmEngineResponse, openAiFeedbackResponse));

        return finalResult;
    }

    public Map<String, Object> createFailureResult(
            String jobId,
            String failedStep,
            String failReason
    ) {
        Map<String, Object> failureResult = new LinkedHashMap<>();

        failureResult.put(ResultSchemaVersion.FIELD, ResultSchemaVersion.CURRENT);
        failureResult.put("jobId", jobId);
        failureResult.put("status", "CANCELLED".equals(failedStep) ? "CANCELLED" : "FAILED");
        failureResult.put("createdAt", LocalDateTime.now().toString());
        failureResult.put("failedStep", failedStep == null ? "UNKNOWN" : failedStep);
        failureResult.put("failReason", failReason == null ? "알 수 없는 오류가 발생했습니다." : failReason);
        failureResult.put("scoreSummary", ScoreSummary.failed());
        failureResult.put("basicAnalysis", createFailedBasicAnalysis());
        failureResult.put("visualAnalysis", createFailedVisualAnalysis());
        failureResult.put("feedback", createFailedFeedback());
        failureResult.put("practicePlan", List.of());
        failureResult.put("timelineFeedback", List.of());
        failureResult.put("notableMoments", List.of());
        failureResult.put("pipeline", createFailedPipeline(failedStep));

        return failureResult;
    }

    private ScoreSummary createScoreSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> score = nullSafeMap(analysisEngineResponse.score());
        int totalScore = getNumberValue(score, "totalScore");

        return new ScoreSummary(
                totalScore,
                getNumberValue(score, "postureScore"),
                getNumberValue(score, "gazeScore"),
                getNumberValue(score, "speechScore"),
                getNumberValue(score, "gestureScore"),
                getNumberValue(score, "expressionScore"),
                resolveLevel(totalScore)
        );
    }

    private Map<String, Object> createBasicAnalysis(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> basicAnalysis = new LinkedHashMap<>();

        basicAnalysis.put("videoInfo", nullSafeMap(analysisEngineResponse.videoInfo()));
        basicAnalysis.put("frame", nullSafeMap(analysisEngineResponse.frame()));
        basicAnalysis.put("audio", nullSafeMap(analysisEngineResponse.audio()));
        basicAnalysis.put("filler", nullSafeMap(analysisEngineResponse.filler()));
        basicAnalysis.put("pose", nullSafeMap(analysisEngineResponse.pose()));
        basicAnalysis.put("gesture", nullSafeMap(analysisEngineResponse.gesture()));
        basicAnalysis.put("face", nullSafeMap(analysisEngineResponse.face()));
        basicAnalysis.put("emotion", nullSafeMap(analysisEngineResponse.emotion()));

        return basicAnalysis;
    }

    private Map<String, Object> createVisualAnalysis(
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> visualAnalysis = new LinkedHashMap<>();

        visualAnalysis.put("status", videoLlmEngineResponse.status());
        visualAnalysis.put("model", nullSafeMap(videoLlmEngineResponse.model()));
        visualAnalysis.put("observations", nullSafeObject(videoLlmEngineResponse.observations()));
        visualAnalysis.put("globalSummary", nullSafeMap(videoLlmEngineResponse.globalSummary()));

        return visualAnalysis;
    }

    private Map<String, Object> createFeedback(
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Map<String, Object> feedback = new LinkedHashMap<>();

        feedback.put("generationMode", nullSafeString(openAiFeedbackResponse.generationMode(), "UNKNOWN"));
        feedback.put("model", nullSafeString(openAiFeedbackResponse.model(), "-"));
        feedback.put("realApiUsed", openAiFeedbackResponse.realApiUsed());
        feedback.put("fallbackReason", nullSafeString(openAiFeedbackResponse.fallbackReason(), "-"));
        feedback.put("overall", nullSafeString(openAiFeedbackResponse.overallFeedback(), "표시할 종합 피드백이 없습니다."));
        feedback.put("strengths", nullSafeStringList(openAiFeedbackResponse.strengths()));
        feedback.put("improvements", nullSafeStringList(openAiFeedbackResponse.improvements()));

        return feedback;
    }

    private List<Map<String, Object>> createNotableMoments(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        List<Map<String, Object>> notableMoments = new ArrayList<>();

        Map<String, Object> postureMoment = createLowestScoreMoment(
                nullSafeMap(analysisEngineResponse.pose()),
                "posture",
                "자세 균형이 가장 흔들린 순간",
                "poseDetected",
                "shoulderBalanceScore"
        );
        if (postureMoment != null) {
            notableMoments.add(postureMoment);
        }

        Map<String, Object> gazeMoment = createLowestScoreMoment(
                nullSafeMap(analysisEngineResponse.face()),
                "gaze",
                "카메라 응시가 가장 흔들린 순간",
                "faceDetected",
                "gazeScore"
        );
        if (gazeMoment != null) {
            notableMoments.add(gazeMoment);
        }

        Map<String, Object> expressionMoment = createLowestScoreMoment(
                nullSafeMap(analysisEngineResponse.emotion()),
                "expression",
                "표정 표현이 가장 약했던 순간",
                "faceDetected",
                "expressionScore"
        );
        if (expressionMoment != null) {
            notableMoments.add(expressionMoment);
        }

        Map<String, Object> gestureMoment = createHighestGestureMovementMoment(
                nullSafeMap(analysisEngineResponse.gesture())
        );
        if (gestureMoment != null) {
            notableMoments.add(gestureMoment);
        }

        return notableMoments;
    }

    private Map<String, Object> createLowestScoreMoment(
            Map<String, Object> analysisSection,
            String category,
            String label,
            String detectedKey,
            String scoreKey
    ) {
        Map<String, Object> selectedFrame = null;
        double selectedValue = Double.MAX_VALUE;

        for (Map<String, Object> frameResult : getFrameResults(analysisSection)) {
            if (!isTrue(frameResult, detectedKey)) {
                continue;
            }

            Double timestampSec = getDoubleValue(frameResult, "timestampSec");
            Double score = getDoubleValue(frameResult, scoreKey);

            if (timestampSec == null || score == null) {
                continue;
            }

            if (score < selectedValue) {
                selectedFrame = frameResult;
                selectedValue = score;
            }
        }

        if (selectedFrame == null) {
            return null;
        }

        return createNotableMoment(
                category,
                label,
                getDoubleValue(selectedFrame, "timestampSec"),
                selectedValue
        );
    }

    private Map<String, Object> createHighestGestureMovementMoment(
            Map<String, Object> gesture
    ) {
        Map<String, Object> selectedFrame = null;
        double selectedValue = Double.NEGATIVE_INFINITY;

        for (Map<String, Object> frameResult : getFrameResults(gesture)) {
            if (!isTrue(frameResult, "gestureDetected")) {
                continue;
            }

            Double timestampSec = getDoubleValue(frameResult, "timestampSec");

            if (timestampSec == null) {
                continue;
            }

            double movement = getDoubleValueOrZero(frameResult, "leftWristMovement")
                    + getDoubleValueOrZero(frameResult, "rightWristMovement");

            if (movement > selectedValue) {
                selectedFrame = frameResult;
                selectedValue = movement;
            }
        }

        if (selectedFrame == null) {
            return null;
        }

        return createNotableMoment(
                "gesture",
                "제스처가 가장 활발했던 순간",
                getDoubleValue(selectedFrame, "timestampSec"),
                selectedValue
        );
    }

    private Map<String, Object> createNotableMoment(
            String category,
            String label,
            Double timestampSec,
            double value
    ) {
        Map<String, Object> notableMoment = new LinkedHashMap<>();

        notableMoment.put("category", category);
        notableMoment.put("label", label);
        notableMoment.put("timestampSec", timestampSec);
        notableMoment.put("value", value);

        return notableMoment;
    }

    private Map<String, Object> createCompletedPipeline(
            VideoLlmEngineResponse videoLlmEngineResponse,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        String videoLlmGenerationMode = resolveVideoLlmGenerationMode(videoLlmEngineResponse);

        pipeline.put("basicAnalysis", "analysis-engine");
        pipeline.put("frameExtraction", "analysis-engine opencv");
        pipeline.put("audioExtraction", "analysis-engine ffmpeg");
        pipeline.put("sttAnalysis", "analysis-engine faster-whisper");
        pipeline.put("poseAnalysis", "analysis-engine mediapipe pose");
        pipeline.put("gestureAnalysis", "analysis-engine mediapipe pose wrist elbow");
        pipeline.put("faceAnalysis", "analysis-engine mediapipe face mesh");
        pipeline.put("emotionAnalysis", "analysis-engine mediapipe face mesh expression");
        pipeline.put("videoLlmAnalysis", resolveVideoLlmPipelineStatus(videoLlmGenerationMode));
        pipeline.put("videoLlmGenerationMode", videoLlmGenerationMode);
        pipeline.put("compactAnalysis", "spring-boot compact");
        pipeline.put("openAiFeedback", resolveOpenAiPipelineStatus(openAiFeedbackResponse));
        pipeline.put("openAiGenerationMode", nullSafeString(openAiFeedbackResponse.generationMode(), "UNKNOWN"));
        pipeline.put("openAiModel", nullSafeString(openAiFeedbackResponse.model(), "-"));
        pipeline.put("openAiRealApiUsed", openAiFeedbackResponse.realApiUsed());
        pipeline.put("openAiFallbackReason", nullSafeString(openAiFeedbackResponse.fallbackReason(), "-"));
        pipeline.put("finalMerge", "spring-boot result merge");

        return pipeline;
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

    private String resolveVideoLlmPipelineStatus(String generationMode) {
        if ("REAL".equals(generationMode)) {
            return "video-llm-engine real";
        }

        if ("FALLBACK".equals(generationMode)) {
            return "video-llm-engine fallback mock";
        }

        if ("MOCK".equals(generationMode)) {
            return "video-llm-engine mock";
        }

        if ("SKIPPED".equals(generationMode)) {
            return "video-llm-engine skipped";
        }

        return "video-llm-engine unknown";
    }

    private String resolveOpenAiPipelineStatus(
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        String generationMode = openAiFeedbackResponse.generationMode();

        if ("REAL".equals(generationMode)) {
            return "openai real";
        }

        if ("FALLBACK".equals(generationMode)) {
            return "openai fallback mock";
        }

        if ("MOCK".equals(generationMode)) {
            return "openai mock";
        }

        if ("SKIPPED".equals(generationMode)) {
            return "openai skipped";
        }

        return "openai unknown";
    }

    private Map<String, Object> createFailedPipeline(String failedStep) {
        Map<String, Object> pipeline = new LinkedHashMap<>();

        pipeline.put("basicAnalysis", resolvePipelineStatus(failedStep, "BASIC_ANALYZING"));
        pipeline.put("videoLlmAnalysis", resolvePipelineStatus(failedStep, "VIDEO_LLM_ANALYZING"));
        pipeline.put("compactAnalysis", resolvePipelineStatus(failedStep, "COMPACTING"));
        pipeline.put("openAiFeedback", resolvePipelineStatus(failedStep, "OPENAI_GENERATING"));
        pipeline.put("finalMerge", resolvePipelineStatus(failedStep, "MERGING_RESULT"));

        return pipeline;
    }

    private Map<String, Object> createFailedBasicAnalysis() {
        Map<String, Object> basicAnalysis = new LinkedHashMap<>();

        basicAnalysis.put("videoInfo", Map.of());
        basicAnalysis.put("frame", Map.of());
        basicAnalysis.put("audio", Map.of());
        basicAnalysis.put("filler", Map.of());
        basicAnalysis.put("pose", Map.of());
        basicAnalysis.put("gesture", Map.of());
        basicAnalysis.put("face", Map.of());
        basicAnalysis.put("emotion", Map.of());

        return basicAnalysis;
    }

    private Map<String, Object> createFailedVisualAnalysis() {
        Map<String, Object> visualAnalysis = new LinkedHashMap<>();

        visualAnalysis.put("model", Map.of());
        visualAnalysis.put("observations", Map.of());
        visualAnalysis.put("globalSummary", Map.of());

        return visualAnalysis;
    }

    private Map<String, Object> createFailedFeedback() {
        Map<String, Object> feedback = new LinkedHashMap<>();

        feedback.put("generationMode", "FAILED");
        feedback.put("model", "-");
        feedback.put("realApiUsed", false);
        feedback.put("fallbackReason", "analysis failed");
        feedback.put("overall", "분석 실행 중 오류가 발생하여 최종 피드백을 생성하지 못했습니다.");
        feedback.put("strengths", List.of());
        feedback.put("improvements", List.of("분석 엔진 상태와 업로드된 영상 파일 경로를 확인하세요."));

        return feedback;
    }

    private String resolvePipelineStatus(String failedStep, String stepName) {
        if (failedStep == null) {
            return "unknown";
        }

        if (failedStep.equals(stepName)) {
            return "failed";
        }

        return "not-guaranteed";
    }

    private Map<String, Object> nullSafeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private Object nullSafeObject(Object value) {
        return value == null ? Map.of() : value;
    }

    private List<?> nullSafeList(List<?> value) {
        return value == null ? List.of() : value;
    }

    private List<String> nullSafeStringList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private List<Map<String, Object>> getFrameResults(Map<String, Object> analysisSection) {
        Object value = analysisSection.get("frameResults");

        if (!(value instanceof List<?> frameResults)) {
            return List.of();
        }

        List<Map<String, Object>> normalizedFrameResults = new ArrayList<>();

        for (Object frameResult : frameResults) {
            if (!(frameResult instanceof Map<?, ?> rawFrameResult)) {
                continue;
            }

            Map<String, Object> normalizedFrameResult = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawFrameResult.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    normalizedFrameResult.put(key, entry.getValue());
                }
            }

            normalizedFrameResults.add(normalizedFrameResult);
        }

        return normalizedFrameResults;
    }

    private boolean isTrue(
            Map<String, Object> map,
            String key
    ) {
        return Boolean.TRUE.equals(map.get(key));
    }

    private Double getDoubleValue(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return null;
    }

    private double getDoubleValueOrZero(
            Map<String, Object> map,
            String key
    ) {
        Double value = getDoubleValue(map, key);

        return value == null ? 0.0 : value;
    }

    private String nullSafeString(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
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
}
