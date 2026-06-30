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
                "practicePlan", openAiFeedbackResponse.practicePlan(),
                "timelineFeedback", openAiFeedbackResponse.timelineFeedback(),

                "pipeline", createPipeline()
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
                "strengths", openAiFeedbackResponse.strengths(),
                "improvements", openAiFeedbackResponse.improvements()
        );
    }

    private Map<String, Object> createPipeline() {
        return Map.of(
                "basicAnalysis", "analysis-engine mock",
                "videoLlmAnalysis", "video-llm-engine mock",
                "compactAnalysis", "spring-boot compact",
                "openAiFeedback", "openai mock",
                "finalMerge", "spring-boot result merge"
        );
    }

    private Map<String, Object> nullSafe(Map<String, Object> value) {
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