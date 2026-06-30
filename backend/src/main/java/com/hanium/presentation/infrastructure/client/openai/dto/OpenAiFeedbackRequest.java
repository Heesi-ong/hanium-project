package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.Map;

public record OpenAiFeedbackRequest(
        String jobId,
        Map<String, Object> compactAnalysis
) {
}