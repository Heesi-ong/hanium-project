package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.entity.UploadedVideo;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record ResultSummaryResponse(
        String jobId,
        AnalysisStatus status,
        String statusDescription,
        String originalFileName,
        String fileName,
        Long fileSize,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        Map<String, Object> scoreSummary,
        Map<String, Object> feedback
) {

    public static ResultSummaryResponse of(
            AnalysisJob analysisJob,
            UploadedVideo uploadedVideo
    ) {
        return of(
                analysisJob,
                uploadedVideo,
                Map.of()
        );
    }

    public static ResultSummaryResponse of(
            AnalysisJob analysisJob,
            UploadedVideo uploadedVideo,
            Map<String, Object> finalResult
    ) {
        return new ResultSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getFileSize(),
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt(),
                extractScoreSummary(finalResult),
                extractFeedback(finalResult)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractScoreSummary(
            Map<String, Object> finalResult
    ) {
        if (finalResult == null) {
            return createEmptyScoreSummary();
        }

        Object scoreSummary = finalResult.get("scoreSummary");

        if (scoreSummary instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return createEmptyScoreSummary();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractFeedback(
            Map<String, Object> finalResult
    ) {
        if (finalResult == null) {
            return createUnknownFeedback();
        }

        Object feedback = finalResult.get("feedback");

        if (feedback instanceof Map<?, ?> map) {
            Map<String, Object> source = (Map<String, Object>) map;

            Map<String, Object> normalizedFeedback = new LinkedHashMap<>();
            normalizedFeedback.put("generationMode", getOrDefault(source, "generationMode", "UNKNOWN"));
            normalizedFeedback.put("model", getOrDefault(source, "model", "-"));
            normalizedFeedback.put("realApiUsed", getOrDefault(source, "realApiUsed", false));
            normalizedFeedback.put("fallbackReason", getOrDefault(source, "fallbackReason", "-"));
            normalizedFeedback.put("overall", getOrDefault(source, "overall", ""));

            return normalizedFeedback;
        }

        return createUnknownFeedback();
    }

    private static Map<String, Object> createEmptyScoreSummary() {
        Map<String, Object> scoreSummary = new LinkedHashMap<>();

        scoreSummary.put("totalScore", 0);
        scoreSummary.put("postureScore", 0);
        scoreSummary.put("gazeScore", 0);
        scoreSummary.put("speechScore", 0);
        scoreSummary.put("gestureScore", 0);
        scoreSummary.put("emotionScore", 0);
        scoreSummary.put("level", "-");

        return scoreSummary;
    }

    private static Map<String, Object> createUnknownFeedback() {
        Map<String, Object> feedback = new LinkedHashMap<>();

        feedback.put("generationMode", "UNKNOWN");
        feedback.put("model", "-");
        feedback.put("realApiUsed", false);
        feedback.put("fallbackReason", "-");
        feedback.put("overall", "");

        return feedback;
    }

    private static Object getOrDefault(
            Map<String, Object> map,
            String key,
            Object defaultValue
    ) {
        Object value = map.get(key);
        return value == null ? defaultValue : value;
    }
}