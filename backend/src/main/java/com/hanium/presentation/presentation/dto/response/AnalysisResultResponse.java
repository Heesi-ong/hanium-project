package com.hanium.presentation.presentation.dto.response;

import java.util.Map;

public record AnalysisResultResponse(
        String jobId,
        Map<String, Object> result,
        String dataIssue,
        String dataIssueDescription
) {

    @SuppressWarnings("unchecked")
    public static AnalysisResultResponse of(String jobId, Map<String, Object> result) {
        Map<String, Object> normalizedResult = ResultSummaryResponse.normalizeFinalResult(result);
        // normalizeFinalResult가 이미 만들어 둔 scoreSummary/feedback을 그대로 재사용합니다.
        // 원본 result를 다시 넘겨 같은 추출 로직을 한 번 더 돌리지 않습니다.
        String dataIssue = ResultSummaryResponse.resolveDataIssue(
                (Map<String, Object>) normalizedResult.get("scoreSummary"),
                (Map<String, Object>) normalizedResult.get("feedback")
        );

        return new AnalysisResultResponse(
                jobId,
                normalizedResult,
                dataIssue,
                ResultSummaryResponse.resolveDataIssueDescription(dataIssue)
        );
    }

    public static AnalysisResultResponse resultDataUnavailable(String jobId) {
        return new AnalysisResultResponse(
                jobId,
                ResultSummaryResponse.normalizeFinalResult(Map.of()),
                "RESULT_DATA_UNAVAILABLE",
                ResultSummaryResponse.resolveDataIssueDescription("RESULT_DATA_UNAVAILABLE")
        );
    }
}
