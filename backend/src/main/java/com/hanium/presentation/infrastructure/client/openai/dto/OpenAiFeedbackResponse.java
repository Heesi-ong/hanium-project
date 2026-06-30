package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;
import java.util.Map;

public record OpenAiFeedbackResponse(
        String jobId,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<Map<String, Object>> practicePlan,
        List<Map<String, Object>> timelineFeedback
) {
}