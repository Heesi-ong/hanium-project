package com.hanium.presentation.infrastructure.client.analysis.dto;

import java.util.Map;

public record AnalysisEngineResponse(
        String jobId,
        String status,
        Map<String, Object> videoInfo,
        Map<String, Object> frame,
        Map<String, Object> audio,
        Map<String, Object> filler,
        Map<String, Object> pose,
        Map<String, Object> face,
        Map<String, Object> score,
        Map<String, Object> error
) {
}