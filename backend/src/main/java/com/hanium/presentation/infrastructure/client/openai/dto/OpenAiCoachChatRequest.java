package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;
import java.util.Map;

public record OpenAiCoachChatRequest(
        String jobId,
        Map<String, Object> compactAnalysis,
        List<ChatTurn> history,
        String newUserMessage
) {
    public record ChatTurn(String role, String content) {
    }
}
