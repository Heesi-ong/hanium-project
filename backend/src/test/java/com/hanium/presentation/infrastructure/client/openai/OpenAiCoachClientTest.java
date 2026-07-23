package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachReplyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCoachClientTest {

    @Test
    void generateReplyReturnsRealReplyWhenApiSucceeds() {
        Fixture fixture = createFixture(true, true);
        fixture.server.expect(requestTo("/v1/responses"))
                .andRespond(withSuccess(
                        completedResponse("속도를 조금만 늦추면 더 좋아질 거예요."),
                        MediaType.APPLICATION_JSON
                ));

        OpenAiCoachReplyResponse response = fixture.client.generateReply(
                new OpenAiCoachChatRequest(
                        "job-1",
                        Map.of("totalScore", 80),
                        List.of(),
                        List.of(),
                        "말이 너무 빠른가요?"
                )
        );

        assertThat(response.generationMode()).isEqualTo("REAL");
        assertThat(response.replyText()).isEqualTo("속도를 조금만 늦추면 더 좋아질 거예요.");
        fixture.server.verify();
    }

    @Test
    void generateReplyIncludesHistorySummaryInRequestBody() {
        Fixture fixture = createFixture(true, true);
        fixture.server.expect(requestTo("/v1/responses"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("과거 발표 점수 이력")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("74")))
                .andRespond(withSuccess(
                        completedResponse("지난번보다 발음이 또렷해졌어요."),
                        MediaType.APPLICATION_JSON
                ));

        OpenAiCoachReplyResponse response = fixture.client.generateReply(
                new OpenAiCoachChatRequest(
                        "job-1",
                        Map.of("totalScore", 80),
                        List.of(Map.of("jobId", "job-0", "scoreSummary", Map.of("totalScore", 74))),
                        List.of(),
                        "저번보다 나아졌나요?"
                )
        );

        assertThat(response.generationMode()).isEqualTo("REAL");
        fixture.server.verify();
    }

    @Test
    void generateReplyFallsBackToMockWhenMonthlyBudgetExceeded() {
        Fixture fixture = createFixture(true, false);

        OpenAiCoachReplyResponse response = fixture.client.generateReply(
                new OpenAiCoachChatRequest("job-2", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).isEqualTo("monthly OpenAI budget exceeded");
    }

    @Test
    void generateReplyReturnsMockWhenOpenAiDisabled() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(false);
        properties.setModel("gpt-test");

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);

        OpenAiCoachClient client = new OpenAiCoachClient(
                properties,
                new CoachPromptBuilder(objectMapper),
                builder.build(),
                userRateLimiter
        );

        OpenAiCoachReplyResponse response = client.generateReply(
                new OpenAiCoachChatRequest("job-3", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).isEqualTo("openai.enabled=false");
    }

    private Fixture createFixture(boolean enabled, boolean rateLimitAllowed) {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(enabled);
        properties.setApiKey("test-api-key");
        properties.setModel("gpt-test");
        properties.setTimeoutMs(15000);

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);
        when(userRateLimiter.tryConsume(eq("openai-monthly"), anyString())).thenReturn(rateLimitAllowed);

        OpenAiCoachClient client = new OpenAiCoachClient(
                properties,
                new CoachPromptBuilder(objectMapper),
                builder.build(),
                userRateLimiter
        );

        return new Fixture(client, server);
    }

    private String completedResponse(String replyText) {
        return """
                {
                  "id": "resp_1",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "%s"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(replyText);
    }

    private record Fixture(
            OpenAiCoachClient client,
            MockRestServiceServer server
    ) {
    }
}
