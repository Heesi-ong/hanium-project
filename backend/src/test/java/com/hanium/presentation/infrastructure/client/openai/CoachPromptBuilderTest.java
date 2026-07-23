package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.infrastructure.client.openai.dto.ChatCompletionApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoachPromptBuilderTest {

    private final CoachPromptBuilder builder = new CoachPromptBuilder(new ObjectMapper());

    @Test
    void systemPromptOmitsHistorySectionWhenHistorySummaryIsEmpty() {
        List<ChatCompletionApiRequest.Message> messages = builder.buildMessages(
                Map.of("totalScore", 80),
                List.of(),
                List.of(),
                "질문입니다."
        );

        String systemPrompt = messages.get(0).content();
        assertThat(systemPrompt).doesNotContain("과거 발표 점수 이력");
    }

    @Test
    void systemPromptIncludesHistorySummaryWhenProvided() {
        List<Map<String, Object>> historySummary = List.of(
                Map.of("jobId", "job-0", "scoreSummary", Map.of("totalScore", 74))
        );

        List<ChatCompletionApiRequest.Message> messages = builder.buildMessages(
                Map.of("totalScore", 80),
                historySummary,
                List.of(),
                "저번보다 나아졌나요?"
        );

        String systemPrompt = messages.get(0).content();
        assertThat(systemPrompt).contains("과거 발표 점수 이력");
        assertThat(systemPrompt).contains("job-0");
        assertThat(systemPrompt).contains("74");
    }

    @Test
    void buildsUserAndAssistantTurnsFromHistoryInOrder() {
        List<OpenAiCoachChatRequest.ChatTurn> history = List.of(
                new OpenAiCoachChatRequest.ChatTurn("USER", "이전 질문"),
                new OpenAiCoachChatRequest.ChatTurn("ASSISTANT", "이전 답변")
        );

        List<ChatCompletionApiRequest.Message> messages = builder.buildMessages(
                Map.of(),
                List.of(),
                history,
                "새 질문"
        );

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("이전 질문");
        assertThat(messages.get(2).role()).isEqualTo("assistant");
        assertThat(messages.get(2).content()).isEqualTo("이전 답변");
        assertThat(messages.get(3).role()).isEqualTo("user");
        assertThat(messages.get(3).content()).isEqualTo("새 질문");
    }
}
