package com.hanium.presentation.infrastructure.client.openai.dto;

public record OpenAiCoachReplyResponse(
        String jobId,
        String generationMode,
        String model,
        boolean realApiUsed,
        String fallbackReason,
        String replyText
) {
    public static OpenAiCoachReplyResponse real(
            String jobId,
            String model,
            String replyText
    ) {
        return new OpenAiCoachReplyResponse(jobId, "REAL", model, true, null, replyText);
    }

    public static OpenAiCoachReplyResponse mock(
            String jobId,
            String model,
            String fallbackReason,
            String replyText
    ) {
        return new OpenAiCoachReplyResponse(jobId, "MOCK", model, false, fallbackReason, replyText);
    }
}
