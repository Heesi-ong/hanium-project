package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.CoachLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachReplyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCoachClientTest {

    @Test
    void generateReplyReturnsRealReplyWhenApiSucceeds() {
        Fixture fixture = createOpenAiFixture(true, true);
        fixture.server.expect(requestTo("/chat/completions"))
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
        assertThat(response.model()).isEqualTo("gpt-test");
        assertThat(response.replyText()).isEqualTo("속도를 조금만 늦추면 더 좋아질 거예요.");
        fixture.server.verify();
    }

    @Test
    void generateReplyIncludesHistorySummaryInRequestBody() {
        Fixture fixture = createOpenAiFixture(true, true);
        fixture.server.expect(requestTo("/chat/completions"))
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

    // 컨테이너 기동 직후 첫 외부 HTTPS 호출이 콜드 DNS/TLS 협상으로 타임아웃되는 것을 실제로
    // 관찰했다(2026-07-23) - 재시도 없이 바로 mock으로 떨어지면 매번 첫 메시지가 낭비된다.
    @Test
    void generateReplyRetriesOnceAfterNetworkErrorThenSucceeds() {
        Fixture fixture = createOpenAiFixture(true, true);
        fixture.server.expect(requestTo("/chat/completions"))
                .andRespond(withException(new IOException("Request timed out")));
        fixture.server.expect(requestTo("/chat/completions"))
                .andRespond(withSuccess(
                        completedResponse("재시도 후 정상 응답입니다."),
                        MediaType.APPLICATION_JSON
                ));

        OpenAiCoachReplyResponse response = fixture.client.generateReply(
                new OpenAiCoachChatRequest("job-retry", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("REAL");
        assertThat(response.replyText()).isEqualTo("재시도 후 정상 응답입니다.");
        fixture.server.verify();
    }

    @Test
    void generateReplyFallsBackToMockWhenBothAttemptsFailWithNetworkError() {
        Fixture fixture = createOpenAiFixture(true, true);
        fixture.server.expect(requestTo("/chat/completions"))
                .andRespond(withException(new IOException("Request timed out")));
        fixture.server.expect(requestTo("/chat/completions"))
                .andRespond(withException(new IOException("Request timed out")));

        OpenAiCoachReplyResponse response = fixture.client.generateReply(
                new OpenAiCoachChatRequest("job-retry-fail", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        fixture.server.verify();
    }

    @Test
    void generateReplyFallsBackToMockWhenMonthlyBudgetExceeded() {
        Fixture fixture = createOpenAiFixture(true, false);

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

        OpenAiCoachClient client = buildClient(properties, openaiProvider(), mock(UserRateLimiter.class));

        OpenAiCoachReplyResponse response = client.generateReply(
                new OpenAiCoachChatRequest("job-3", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).isEqualTo("openai.enabled=false");
    }

    @Test
    void plainMockReplyLogsCoachLlmMode() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(false);
        properties.setModel("gpt-test");

        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiCoachClient.class);
        logger.addAppender(appender);

        try {
            buildClient(properties, openaiProvider(), mock(UserRateLimiter.class))
                    .generateReply(new OpenAiCoachChatRequest(
                            "job-mock-log", Map.of(), List.of(), List.of(), "질문입니다."
                    ));

            assertThat(appender.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("COACH_LLM_MODE")
                            .contains("jobId=job-mock-log")
                            .contains("mode=MOCK")
                            .contains("provider=openai")
                            .contains("reason=openai.enabled=false"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // coach.llm.provider=nvidia일 때: build.nvidia.com(Chat Completions 호환)로 요청이
    // 나가고, NVIDIA 전용 모델이 쓰이며, OpenAI 전용 월간 예산은 건드리지 않아야 한다.
    @Test
    void generateReplyUsesNvidiaProviderWhenConfigured() {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setEnabled(false);
        openAiProperties.setTimeoutMs(15000);

        CoachLlmProperties coachLlmProperties = nvidiaProvider();

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);

        server.expect(requestTo("/chat/completions"))
                .andRespond(withSuccess(
                        completedResponse("자세가 안정적이에요."),
                        MediaType.APPLICATION_JSON
                ));

        OpenAiCoachClient client = new OpenAiCoachClient(
                openAiProperties,
                coachLlmProperties,
                new CoachPromptBuilder(objectMapper),
                builder.build(),
                userRateLimiter
        );

        OpenAiCoachReplyResponse response = client.generateReply(
                new OpenAiCoachChatRequest("job-4", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("REAL");
        assertThat(response.model()).isEqualTo("meta/llama-3.1-70b-instruct");
        server.verify();
        verify(userRateLimiter, never()).tryConsume(eq("openai-monthly"), anyString());
    }

    @Test
    void generateReplyReturnsMockWhenNvidiaApiKeyIsEmpty() {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        CoachLlmProperties coachLlmProperties = new CoachLlmProperties();
        coachLlmProperties.setProvider("nvidia");
        coachLlmProperties.setNvidiaModel("meta/llama-3.1-70b-instruct");
        coachLlmProperties.setNvidiaBaseUrl("https://integrate.api.nvidia.com/v1");
        coachLlmProperties.setNvidiaApiKey("");

        OpenAiCoachClient client = buildClient(openAiProperties, coachLlmProperties, mock(UserRateLimiter.class));

        OpenAiCoachReplyResponse response = client.generateReply(
                new OpenAiCoachChatRequest("job-5", Map.of(), List.of(), List.of(), "질문입니다.")
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).isEqualTo("NVIDIA_API_KEY is empty");
    }

    private Fixture createOpenAiFixture(boolean enabled, boolean rateLimitAllowed) {
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
                openaiProvider(),
                new CoachPromptBuilder(objectMapper),
                builder.build(),
                userRateLimiter
        );

        return new Fixture(client, server);
    }

    private OpenAiCoachClient buildClient(
            OpenAiProperties openAiProperties,
            CoachLlmProperties coachLlmProperties,
            UserRateLimiter userRateLimiter
    ) {
        RestClient.Builder builder = RestClient.builder();
        return new OpenAiCoachClient(
                openAiProperties,
                coachLlmProperties,
                new CoachPromptBuilder(new ObjectMapper()),
                builder.build(),
                userRateLimiter
        );
    }

    private CoachLlmProperties openaiProvider() {
        CoachLlmProperties properties = new CoachLlmProperties();
        properties.setProvider("openai");
        return properties;
    }

    private CoachLlmProperties nvidiaProvider() {
        CoachLlmProperties properties = new CoachLlmProperties();
        properties.setProvider("nvidia");
        properties.setNvidiaApiKey("nvapi-test-key");
        properties.setNvidiaBaseUrl("https://integrate.api.nvidia.com/v1");
        properties.setNvidiaModel("meta/llama-3.1-70b-instruct");
        return properties;
    }

    private String completedResponse(String replyText) {
        return """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 40,
                    "total_tokens": 160
                  }
                }
                """.formatted(replyText);
    }

    private record Fixture(
            OpenAiCoachClient client,
            MockRestServiceServer server
    ) {
    }
}
