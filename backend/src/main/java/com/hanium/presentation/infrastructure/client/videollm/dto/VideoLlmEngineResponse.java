package com.hanium.presentation.infrastructure.client.videollm.dto;

import java.util.List;
import java.util.Map;

public record VideoLlmEngineResponse(
        String jobId,
        String status,
        Map<String, Object> model,
        Map<String, List<Map<String, Object>>> observations,
        Map<String, Object> globalSummary
) {
}