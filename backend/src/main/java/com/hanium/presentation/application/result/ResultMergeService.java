package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
        return Map.of(
                "jobId", jobId,
                "status", "COMPLETED",
                "createdAt", LocalDateTime.now().toString(),

                "scoreSummary", createScoreSummary(analysisEngineResponse),
                "basicAnalysis", createBasicAnalysis(analysisEngineResponse),
                "visualAnalysis", createVisualAnalysis(videoLlmEngineResponse),

                "feedback", createFeedback(openAiFeedbackResponse),
                "practicePlan", nullSafeList(openAiFeedbackResponse.practicePlan()),
                "timelineFeedback", nullSafeList(openAiFeedbackResponse.timelineFeedback()),

                "pipeline", createCompletedPipeline()
        );
    }

    public Map<String, Object> createFailureResult(
            String jobId,
            String failedStep,
            String failReason
    ) {
        return Map.of(
                "jobId", jobId,
                "status", "FAILED",
                "createdAt", LocalDateTime.now().toString(),
                "failedStep", failedStep == null ? "UNKNOWN" : failedStep,
                "failReason", failReason == null ? "알 수 없는 오류가 발생했습니다." : failReason,

                "scoreSummary", Map.of(
                        "totalScore", 0,
                        "postureScore", 0,
                        "gazeScore", 0,
                        "speechScore", 0,
                        "level", "FAILED"
                ),

                "basicAnalysis", Map.of(
                        "videoInfo", Map.of(),
                        "frame", Map.of(),
                        "audio", Map.of(),
                        "filler", Map.of(),
                        "pose", Map.of(),
                        "face", Map.of()
                ),

                "visualAnalysis", Map.of(
                        "model", Map.of(),
                        "observations", Map.of(),
                        "globalSummary", Map.of()
                ),

                "feedback", Map.of(
                        "overall", "분석 실행 중 오류가 발생하여 최종 피드백을 생성하지 못했습니다.",
                        "strengths", List.of(),
                        "improvements", List.of("분석 엔진 상태와 업로드된 영상 파일 경로를 확인하세요.")
                ),

                "practicePlan", List.of(),
                "timelineFeedback", List.of(),
                "pipeline", createFailedPipeline(failedStep)
        );
    }

    private Map<String, Object> createScoreSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> score = nullSafe(analysisEngineResponse.score());

        return Map.of(
                "totalScore", getOrDefault(score, "totalScore", 0),
                "postureScore", getOrDefault(score, "postureScore", 0),
                "gazeScore", getOrDefault(score, "gazeScore", 0),
                "speechScore", getOrDefault(score, "speechScore", 0),
                "level", resolveLevel(getNumberValue(score, "totalScore"))
        );
    }

    private Map<String, Object> createBasicAnalysis(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        return Map.of(
                "videoInfo", nullSafe(analysisEngineResponse.videoInfo()),
                "frame", nullSafe(analysisEngineResponse.frame()),
                "audio", nullSafe(analysisEngineResponse.audio()),
                "filler", nullSafe(analysisEngineResponse.filler()),
                "pose", nullSafe(analysisEngineResponse.pose()),
                "face", nullSafe(analysisEngineResponse.face())
        );
    }

    private Map<String, Object> createVisualAnalysis(
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        return Map.of(
                "model", nullSafe(videoLlmEngineResponse.model()),
                "observations", nullSafe(videoLlmEngineResponse.observations()),
                "globalSummary", nullSafe(videoLlmEngineResponse.globalSummary())
        );
    }

    private Map<String, Object> createFeedback(
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        return Map.of(
                "overall", openAiFeedbackResponse.overallFeedback(),
                "strengths", nullSafeStringList(openAiFeedbackResponse.strengths()),
                "improvements", nullSafeStringList(openAiFeedbackResponse.improvements())
        );
    }

    private Map<String, Object> createCompletedPipeline() {
        return Map.of(
                "basicAnalysis", "analysis-engine",
                "frameExtraction", "analysis-engine opencv",
                "videoLlmAnalysis", "video-llm-engine mock",
                "compactAnalysis", "spring-boot compact",
                "openAiFeedback", "openai mock",
                "finalMerge", "spring-boot result merge"
        );
    }

    private Map<String, Object> createFailedPipeline(String failedStep) {
        return Map.of(
                "basicAnalysis", resolvePipelineStatus(failedStep, "BASIC_ANALYZING"),
                "videoLlmAnalysis", resolvePipelineStatus(failedStep, "VIDEO_LLM_ANALYZING"),
                "compactAnalysis", resolvePipelineStatus(failedStep, "COMPACTING"),
                "openAiFeedback", resolvePipelineStatus(failedStep, "OPENAI_GENERATING"),
                "finalMerge", resolvePipelineStatus(failedStep, "MERGING_RESULT")
        );
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

    private Map<String, Object> nullSafe(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private List<Map<String, Object>> nullSafeList(List<Map<String, Object>> value) {
        return value == null ? List.of() : value;
    }

    private List<String> nullSafeStringList(List<String> value) {
        return value == null ? List.of() : value;
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