package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;

public record ChatCompletionApiResponse(
        List<Choice> choices,
        Usage usage
) {
    public String extractContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            return null;
        }

        return choices.get(0).message().content();
    }

    public record Choice(Message message) {
    }

    public record Message(String role, String content) {
    }

    public record Usage(Integer prompt_tokens, Integer completion_tokens, Integer total_tokens) {
    }
}
