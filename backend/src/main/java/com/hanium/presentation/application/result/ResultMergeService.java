package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

        finalResult.put("jobId", jobId);
        finalResult.put("status", "COMPLETED");
        finalResult.put("createdAt", LocalDateTime.now().toString());
        finalResult.put("scoreSummary", createScoreSummary(analysisEngineResponse));
        finalResult.put("basicAnalysis", createBasicAnalysis(analysisEngineResponse));
        finalResult.put("visualAnalysis", createVisualAnalysis(videoLlmEngineResponse));
        finalResult.put("feedback", createFeedback(openAiFeedbackResponse));
        finalResult.put("practicePlan", nullSafeList(openAiFeedbackResponse.practicePlan()));
        finalResult.put("timelineFeedback", nullSafeList(openAiFeedbackResponse.timelineFeedback()));
        finalResult.put("pipeline", createCompletedPipeline(openAiFeedbackResponse));

        return finalResult;
    }

    public Map<String, Object> createFailureResult(
            String jobId,
            String failedStep,
            String failReason
    ) {
        Map<String, Object> failureResult = new LinkedHashMap<>();

        failureResult.put("jobId", jobId);
        failureResult.put("status", "FAILED");
        failureResult.put("createdAt", LocalDateTime.now().toString());
        failureResult.put("failedStep", failedStep == null ? "UNKNOWN" : failedStep);
        failureResult.put("failReason", failReason == null ? "알 수 없는 오류가 발생했습니다." : failReason);
        failureResult.put("scoreSummary", createFailedScoreSummary());
        failureResult.put("basicAnalysis", createFailedBasicAnalysis());
        failureResult.put("visualAnalysis", createFailedVisualAnalysis());
        failureResult.put("feedback", createFailedFeedback());
        failureResult.put("practicePlan", List.of());
        failureResult.put("timelineFeedback", List.of());
        failureResult.put("pipeline", createFailedPipeline(failedStep));

        return failureResult;
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
        scoreSummary.put("expressionScore", getOrDefault(score, "expressionScore", 0));
        scoreSummary.put("level", resolveLevel(getNumberValue(score, "totalScore")));

        return scoreSummary;
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

    private Map<String, Object> createCompletedPipeline(
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Map<String, Object> pipeline = new LinkedHashMap<>();

        pipeline.put("basicAnalysis", "analysis-engine");
        pipeline.put("frameExtraction", "analysis-engine opencv");
        pipeline.put("audioExtraction", "analysis-engine ffmpeg");
        pipeline.put("sttAnalysis", "analysis-engine faster-whisper");
        pipeline.put("poseAnalysis", "analysis-engine mediapipe pose");
        pipeline.put("gestureAnalysis", "analysis-engine mediapipe pose wrist elbow");
        pipeline.put("faceAnalysis", "analysis-engine mediapipe face mesh");
        pipeline.put("emotionAnalysis", "analysis-engine mediapipe face mesh expression");
        pipeline.put("videoLlmAnalysis", "video-llm-engine mock");
        pipeline.put("compactAnalysis", "spring-boot compact");
        pipeline.put("openAiFeedback", resolveOpenAiPipelineStatus(openAiFeedbackResponse));
        pipeline.put("openAiGenerationMode", nullSafeString(openAiFeedbackResponse.generationMode(), "UNKNOWN"));
        pipeline.put("openAiModel", nullSafeString(openAiFeedbackResponse.model(), "-"));
        pipeline.put("openAiRealApiUsed", openAiFeedbackResponse.realApiUsed());
        pipeline.put("openAiFallbackReason", nullSafeString(openAiFeedbackResponse.fallbackReason(), "-"));
        pipeline.put("finalMerge", "spring-boot result merge");

        return pipeline;
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

    private Map<String, Object> createFailedScoreSummary() {
        Map<String, Object> scoreSummary = new LinkedHashMap<>();

        scoreSummary.put("totalScore", 0);
        scoreSummary.put("postureScore", 0);
        scoreSummary.put("gazeScore", 0);
        scoreSummary.put("speechScore", 0);
        scoreSummary.put("gestureScore", 0);
        scoreSummary.put("expressionScore", 0);
        scoreSummary.put("level", "FAILED");

        return scoreSummary;
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

    private String nullSafeString(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
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