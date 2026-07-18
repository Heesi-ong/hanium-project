package com.hanium.presentation.presentation.dto.response;

import java.util.Map;

public record AnalysisResultResponse(
        String jobId,
        Map<String, Object> result,
        String dataIssue,
        String dataIssueDescription
) {

    public static AnalysisResultResponse of(String jobId, Map<String, Object> result) {
        Map<String, Object> normalizedResult = ResultSummaryResponse.normalizeFinalResult(result);
        String dataIssue = ResultSummaryResponse.resolveDataIssue(normalizedResult);

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
