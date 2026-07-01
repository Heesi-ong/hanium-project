package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;

public record OpenAiResponsesApiResponse(
        String id,
        String object,
        String status,
        String model,
        List<Output> output,
        Usage usage,
        ErrorBody error
) {
    public String extractOutputText() {
        if (output == null || output.isEmpty()) {
            return "";
        }

        return output.stream()
                .filter(item -> "message".equals(item.type()))
                .filter(item -> item.content() != null)
                .flatMap(item -> item.content().stream())
                .filter(content -> "output_text".equals(content.type()))
                .map(Content::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
    }

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public record Output(
            String id,
            String type,
            String status,
            String role,
            List<Content> content
    ) {
    }

    public record Content(
            String type,
            String text
    ) {
    }

    public record Usage(
            Integer input_tokens,
            Integer output_tokens,
            Integer total_tokens
    ) {
    }

    public record ErrorBody(
            String code,
            String message,
            String type
    ) {
    }
}