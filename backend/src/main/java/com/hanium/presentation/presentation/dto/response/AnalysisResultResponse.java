package com.hanium.presentation.presentation.dto.response;

import java.util.Map;

public record AnalysisResultResponse(
        String jobId,
        Map<String, Object> result,
        String dataIssue,
        String dataIssueDescription
) {

    public static AnalysisResultResponse of(String jobId, Map<String, Object> result) {
        String dataIssue = ResultSummaryResponse.resolveDataIssue(result);

        return new AnalysisResultResponse(
                jobId,
                result,
                dataIssue,
                ResultSummaryResponse.resolveDataIssueDescription(dataIssue)
        );
    }
}
