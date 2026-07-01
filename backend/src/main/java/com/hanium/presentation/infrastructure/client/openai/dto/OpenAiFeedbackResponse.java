package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;

public record OpenAiFeedbackResponse(
        String jobId,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<?> practicePlan,
        List<?> timelineFeedback
) {
}