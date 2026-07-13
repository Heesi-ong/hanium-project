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
        Map<String, Object> feedback,
        // 정상 항목은 null입니다. UploadedVideo 레코드가 없는 등 데이터 부분 손상이 있을 때만
        // 값이 채워지며, 목록 조회는 이 항목을 실패시키지 않고 손상 사실만 표시한 채 반환합니다.
        String dataIssue,
        String dataIssueDescription
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
                extractFeedback(finalResult),
                null,
                null
        );
    }

    // 업로드된 영상 레코드를 찾지 못한(데이터 정합성이 깨진) 작업을 위한 항목입니다.
    // 목록 전체를 실패시키는 대신, 이 항목만 손상 상태로 표시해 나머지 결과는 정상 반환합니다.
    public static ResultSummaryResponse missingVideo(
            AnalysisJob analysisJob,
            Map<String, Object> finalResult
    ) {
        return new ResultSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                "(영상 정보 없음)",
                "(영상 정보 없음)",
                0L,
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt(),
                extractScoreSummary(finalResult),
                extractFeedback(finalResult),
                "MISSING_VIDEO",
                "업로드된 영상 정보를 찾을 수 없습니다. 관리자에게 문의하세요."
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
        scoreSummary.put("expressionScore", 0);
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