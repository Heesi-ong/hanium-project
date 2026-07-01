package com.hanium.presentation.infrastructure.client.openai.dto;

import java.util.List;
import java.util.Map;

public record OpenAiResponsesApiRequest(
        String model,
        List<InputMessage> input,
        TextFormat text,
        Integer max_output_tokens,
        Boolean store
) {
    public static OpenAiResponsesApiRequest create(
            String model,
            String systemPrompt,
            String userPrompt
    ) {
        return new OpenAiResponsesApiRequest(
                model,
                List.of(
                        InputMessage.system(systemPrompt),
                        InputMessage.user(userPrompt)
                ),
                TextFormat.jsonSchema(),
                2000,
                false
        );
    }

    public record InputMessage(
            String role,
            List<Content> content
    ) {
        public static InputMessage system(String text) {
            return new InputMessage(
                    "system",
                    List.of(Content.inputText(text))
            );
        }

        public static InputMessage user(String text) {
            return new InputMessage(
                    "user",
                    List.of(Content.inputText(text))
            );
        }
    }

    public record Content(
            String type,
            String text
    ) {
        public static Content inputText(String text) {
            return new Content("input_text", text);
        }
    }

    public record TextFormat(
            Format format
    ) {
        public static TextFormat jsonSchema() {
            return new TextFormat(
                    new Format(
                            "json_schema",
                            "presentation_feedback_response",
                            "AI 발표 코칭 결과를 프론트엔드가 그대로 표시할 수 있는 JSON 구조로 반환합니다.",
                            true,
                            createSchema()
                    )
            );
        }

        private static Map<String, Object> createSchema() {
            return Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of(
                            "overall",
                            "strengths",
                            "improvements",
                            "practicePlan",
                            "timelineFeedback"
                    ),
                    "properties", Map.of(
                            "overall", Map.of(
                                    "type", "string",
                                    "description", "발표 전체에 대한 종합 피드백"
                            ),
                            "strengths", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "발표의 강점 목록"
                            ),
                            "improvements", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "발표의 개선점 목록"
                            ),
                            "practicePlan", Map.of(
                                    "type", "array",
                                    "description", "사용자가 바로 따라 할 수 있는 연습 계획",
                                    "items", Map.of(
                                            "type", "object",
                                            "additionalProperties", false,
                                            "required", List.of(
                                                    "title",
                                                    "description",
                                                    "duration"
                                            ),
                                            "properties", Map.of(
                                                    "title", Map.of("type", "string"),
                                                    "description", Map.of("type", "string"),
                                                    "duration", Map.of("type", "string")
                                            )
                                    )
                            ),
                            "timelineFeedback", Map.of(
                                    "type", "array",
                                    "description", "분석 영역별 타임라인 피드백",
                                    "items", Map.of(
                                            "type", "object",
                                            "additionalProperties", false,
                                            "required", List.of(
                                                    "category",
                                                    "title",
                                                    "summary",
                                                    "recommendation"
                                            ),
                                            "properties", Map.of(
                                                    "category", Map.of("type", "string"),
                                                    "title", Map.of("type", "string"),
                                                    "summary", Map.of("type", "string"),
                                                    "recommendation", Map.of("type", "string")
                                            )
                                    )
                            )
                    )
            );
        }
    }

    public record Format(
            String type,
            String name,
            String description,
            Boolean strict,
            Map<String, Object> schema
    ) {
    }
}