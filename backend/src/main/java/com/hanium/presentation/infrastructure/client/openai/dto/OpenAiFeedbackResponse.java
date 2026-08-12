package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;

public record OpenAiFeedbackResponse(
        String jobId,
        String generationMode,
        String model,
        boolean realApiUsed,
        String fallbackReason,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<?> practicePlan,
        List<?> timelineFeedback
) {
    public OpenAiFeedbackResponse(
            String jobId,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<?> practicePlan,
            List<?> timelineFeedback
    ) {
        this(
                jobId,
                "MOCK",
                null,
                false,
                "legacy constructor",
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }

    public static OpenAiFeedbackResponse mock(
            String jobId,
            String model,
            String fallbackReason,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<?> practicePlan,
            List<?> timelineFeedback
    ) {
        return new OpenAiFeedbackResponse(
                jobId,
                "MOCK",
                model,
                false,
                fallbackReason,
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }

    public static OpenAiFeedbackResponse real(
            String jobId,
            String model,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<?> practicePlan,
            List<?> timelineFeedback
    ) {
        return new OpenAiFeedbackResponse(
                jobId,
                "REAL",
                model,
                true,
                null,
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }

    public static OpenAiFeedbackResponse fallback(
            String jobId,
            String model,
            String fallbackReason,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<?> practicePlan,
            List<?> timelineFeedback
    ) {
        return new OpenAiFeedbackResponse(
                jobId,
                "FALLBACK",
                model,
                false,
                fallbackReason,
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }

    public static OpenAiFeedbackResponse skipped(
            String jobId,
            String reason,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<?> practicePlan,
            List<?> timelineFeedback
    ) {
        return new OpenAiFeedbackResponse(
                jobId,
                "SKIPPED",
                null,
                false,
                reason,
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }
}
