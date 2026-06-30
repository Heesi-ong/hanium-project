package com.hanium.presentation.presentation.dto.response;

import java.util.Map;

public record AnalysisResultResponse(
        String jobId,
        Map<String, Object> result
) {

    public static AnalysisResultResponse of(String jobId, Map<String, Object> result) {
        return new AnalysisResultResponse(jobId, result);
    }
}