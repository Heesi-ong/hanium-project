package com.hanium.presentation.infrastructure.client.openai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiClientTest {

    @Test
    void generateFeedbackLogsOpenAiUsageWhenUsageExists() {
        OpenAiClientFixture fixture = createFixture();
        ListAppender<ILoggingEvent> appender = attachListAppender();

        fixture.server.expect(requestTo("/v1/responses"))
                .andRespond(withSuccess(completedResponseWithUsage(), MediaType.APPLICATION_JSON));

        try {
            OpenAiFeedbackResponse response = fixture.client.generateFeedback(
                    new OpenAiFeedbackRequest("usage-job", Map.of())
            );

            assertThat(response.generationMode()).isEqualTo("REAL");
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("OPENAI_USAGE")
                            .contains("jobId=usage-job")
                            .contains("model=gpt-test")
                            .contains("inputTokens=11")
                            .contains("outputTokens=22")
                            .contains("totalTokens=33"));
        } finally {
            detachListAppender(appender);
        }

        fixture.server.verify();
    }

    @Test
    void generateFeedbackLogsOpenAiUsageMissingWhenUsageIsNull() {
        OpenAiClientFixture fixture = createFixture();
        ListAppender<ILoggingEvent> appender = attachListAppender();

        fixture.server.expect(requestTo("/v1/responses"))
                .andRespond(withSuccess(completedResponseWithoutUsage(), MediaType.APPLICATION_JSON));

        try {
            OpenAiFeedbackResponse response = fixture.client.generateFeedback(
                    new OpenAiFeedbackRequest("no-usage-job", Map.of())
            );

            assertThat(response.generationMode()).isEqualTo("REAL");
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("OPENAI_USAGE")
                            .contains("jobId=no-usage-job")
                            .contains("model=gpt-test")
                            .contains("usage=none"));
        } finally {
            detachListAppender(appender);
        }

        fixture.server.verify();
    }

    // NVIDIA의 json_object 모드는 strict json_schema와 달리 필드를 통째로 생략해도 문법적으로는
    // 유효한 JSON이 된다 - 실제로 overall만 채우고 나머지는 비운 응답을 실측에서 관찰했다
    // (2026-07-23). 이런 응답은 실패로 간주해 mock으로 폴백해야 한다(반쪽짜리 결과를 사용자에게
    // 보여주지 않기 위함).
    @Test
    void generateFeedbackFallsBackToMockWhenRequiredFieldsAreMissing() {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setEnabled(false);
        openAiProperties.setTimeoutMs(15000);

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String incompleteResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "{\\"overall\\":\\"발표 전체적으로는 48점입니다.\\"}"
                      }
                    }
                  ]
                }
                """;
        server.expect(requestTo("/chat/completions"))
                .andRespond(withSuccess(incompleteResponse, MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(
                openAiProperties,
                nvidiaFeedbackProvider(),
                new OpenAiPromptBuilder(objectMapper),
                RestClient.builder().build(),
                builder.build(),
                objectMapper,
                mock(UserRateLimiter.class)
        );

        OpenAiFeedbackResponse response = client.generateFeedback(
                new OpenAiFeedbackRequest("nvidia-incomplete-job", Map.of())
        );

        // 필드 누락은 네트워크 오류가 아니므로 재시도하지 않고 바로 mock으로 폴백한다
        // (같은 프롬프트로 재시도해도 같은 불완전 응답이 나올 가능성이 높기 때문).
        assertThat(response.generationMode()).isEqualTo("FALLBACK");
        server.verify();
    }

    @Test
    void generateMockFeedbackSuggestsStructureMarkersWhenTranscriptNeedsStructure() {
        OpenAiClient client = createDisabledClient();

        OpenAiFeedbackResponse response = client.generateFeedback(
                new OpenAiFeedbackRequest(
                        "content-structure-job",
                        compactAnalysisWithUnstructuredTranscript()
                )
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.improvements())
                .anySatisfy(improvement -> assertThat(improvement)
                        .contains("구조 표지")
                        .contains("먼저")
                        .contains("다음으로")
                        .contains("결론적으로")
                        .contains("문장 수: 4")
                        .contains("구조 표지: 0"));
    }

    @Test
    void generateMockFeedbackDoesNotExposeGazeOrExpressionScores() {
        OpenAiClient client = createDisabledClient();

        OpenAiFeedbackResponse response = client.generateFeedback(
                new OpenAiFeedbackRequest(
                        "visual-score-exclusion-job",
                        compactAnalysisWithUnstructuredTranscript()
                )
        );

        assertThat(response.toString())
                .doesNotContain("시선", "표정", "gaze", "expression");
    }

    // feedback.llm.provider=nvidia일 때: Chat Completions(build.nvidia.com)로 요청이 나가고,
    // NVIDIA 전용 모델이 쓰이며, 기존 json_schema 파싱 로직(getString/getStringList 등)이
    // json_object 응답도 그대로 파싱할 수 있어야 한다.
    @Test
    void generateFeedbackUsesNvidiaProviderWhenConfigured() {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setEnabled(false);
        openAiProperties.setTimeoutMs(15000);

        FeedbackLlmProperties feedbackLlmProperties = nvidiaFeedbackProvider();

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);

        server.expect(requestTo("/chat/completions"))
                .andRespond(withSuccess(chatCompletionsResponse(), MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(
                openAiProperties,
                feedbackLlmProperties,
                new OpenAiPromptBuilder(objectMapper),
                RestClient.builder().build(),
                builder.build(),
                objectMapper,
                userRateLimiter
        );

        OpenAiFeedbackResponse response = client.generateFeedback(
                new OpenAiFeedbackRequest("nvidia-job", Map.of())
        );

        assertThat(response.generationMode()).isEqualTo("REAL");
        assertThat(response.model()).isEqualTo("meta/llama-3.1-70b-instruct");
        assertThat(response.overallFeedback()).isEqualTo("좋습니다.");
        assertThat(response.strengths()).containsExactly("안정적입니다.");
        server.verify();
        verify(userRateLimiter, never()).tryConsume(eq("openai-monthly"), anyString());
    }

    @Test
    void generateFeedbackReturnsMockWhenNvidiaApiKeyIsEmpty() {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        FeedbackLlmProperties feedbackLlmProperties = new FeedbackLlmProperties();
        feedbackLlmProperties.setProvider("nvidia");
        feedbackLlmProperties.setNvidiaApiKey("");
        feedbackLlmProperties.setNvidiaModel("meta/llama-3.1-70b-instruct");
        feedbackLlmProperties.setNvidiaBaseUrl("https://integrate.api.nvidia.com/v1");

        OpenAiClient client = new OpenAiClient(
                openAiProperties,
                feedbackLlmProperties,
                new OpenAiPromptBuilder(new ObjectMapper()),
                RestClient.builder().build(),
                RestClient.builder().build(),
                new ObjectMapper(),
                mock(UserRateLimiter.class)
        );

        OpenAiFeedbackResponse response = client.generateFeedback(
                new OpenAiFeedbackRequest("nvidia-no-key-job", Map.of())
        );

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).isEqualTo("NVIDIA_API_KEY is empty");
    }

    // 컨테이너 기동 직후 첫 외부 HTTPS 호출이 콜드 DNS/TLS 협상으로 타임아웃되는 것을 코치
    // 채팅에서 실제로 관찰했다(2026-07-23) - 피드백 생성도 같은 재시도 로직을 공유한다.
    @Test
    void generateFeedbackRetriesOnceAfterNetworkErrorThenSucceeds() {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setEnabled(false);
        openAiProperties.setTimeoutMs(15000);

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("/chat/completions"))
                .andRespond(withException(new IOException("Request timed out")));
        server.expect(requestTo("/chat/completions"))
                .andRespond(withSuccess(chatCompletionsResponse(), MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(
                openAiProperties,
                nvidiaFeedbackProvider(),
                new OpenAiPromptBuilder(objectMapper),
                RestClient.builder().build(),
                builder.build(),
                objectMapper,
                mock(UserRateLimiter.class)
        );

        OpenAiFeedbackResponse response = client.generateFeedback(
                new OpenAiFeedbackRequest("nvidia-retry-job", Map.of())
        );

        assertThat(response.generationMode()).isEqualTo("REAL");
        server.verify();
    }

    private FeedbackLlmProperties nvidiaFeedbackProvider() {
        FeedbackLlmProperties properties = new FeedbackLlmProperties();
        properties.setProvider("nvidia");
        properties.setNvidiaApiKey("nvapi-test-key");
        properties.setNvidiaBaseUrl("https://integrate.api.nvidia.com/v1");
        properties.setNvidiaModel("meta/llama-3.1-70b-instruct");
        return properties;
    }

    private String chatCompletionsResponse() {
        return """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "{\\"overall\\":\\"좋습니다.\\",\\"strengths\\":[\\"안정적입니다.\\"],\\"improvements\\":[\\"속도를 조절하세요.\\"],\\"practicePlan\\":[{\\"title\\":\\"연습\\",\\"description\\":\\"설명\\",\\"duration\\":\\"5분\\"}],\\"timelineFeedback\\":[{\\"category\\":\\"speech\\",\\"title\\":\\"음성\\",\\"summary\\":\\"요약\\",\\"recommendation\\":\\"권장\\"}]}"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 500,
                    "completion_tokens": 120,
                    "total_tokens": 620
                  }
                }
                """;
    }

    private OpenAiClientFixture createFixture() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-api-key");
        properties.setModel("gpt-test");
        properties.setTimeoutMs(15000);

        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);
        when(userRateLimiter.tryConsume(eq("openai-monthly"), anyString())).thenReturn(true);
        OpenAiClient client = new OpenAiClient(
                properties,
                openaiFeedbackProvider(),
                new OpenAiPromptBuilder(objectMapper),
                builder.build(),
                RestClient.builder().build(),
                objectMapper,
                userRateLimiter
        );

        return new OpenAiClientFixture(client, server);
    }

    private OpenAiClient createDisabledClient() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(false);
        properties.setModel("gpt-test");

        ObjectMapper objectMapper = new ObjectMapper();
        return new OpenAiClient(
                properties,
                openaiFeedbackProvider(),
                new OpenAiPromptBuilder(objectMapper),
                RestClient.builder().build(),
                RestClient.builder().build(),
                objectMapper,
                mock(UserRateLimiter.class)
        );
    }

    private FeedbackLlmProperties openaiFeedbackProvider() {
        FeedbackLlmProperties properties = new FeedbackLlmProperties();
        properties.setProvider("openai");
        return properties;
    }

    private Map<String, Object> compactAnalysisWithUnstructuredTranscript() {
        return Map.of(
                "modelInputs", Map.of(
                        "scoreSummary", Map.of(
                                "totalScore", 82,
                                "postureScore", 82,
                                "gazeScore", 82,
                                "speechScore", 82,
                                "gestureScore", 82,
                                "expressionScore", 82
                        ),
                        "speechSummary", Map.of(
                                "fillerScore", 100,
                                "fillerCount", 0
                        ),
                        "visualSummary", Map.of(),
                        "transcriptSummary", Map.of(
                                "sttSuccess", true,
                                "contentStructure", Map.of(
                                        "structureHint", "needs_structure_markers",
                                        "sentenceCount", 4,
                                        "transitionMarkerCount", 0
                                )
                        ),
                        "feedbackFocus", Map.of()
                )
        );
    }

    private ListAppender<ILoggingEvent> attachListAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachListAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiClient.class);
        logger.detachAppender(appender);
    }

    private String completedResponseWithUsage() {
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
                          "text": "{\\"overall\\":\\"좋습니다.\\",\\"strengths\\":[\\"안정적입니다.\\"],\\"improvements\\":[\\"속도를 조절하세요.\\"],\\"practicePlan\\":[{\\"title\\":\\"연습\\",\\"description\\":\\"설명\\",\\"duration\\":\\"5분\\"}],\\"timelineFeedback\\":[{\\"category\\":\\"speech\\",\\"title\\":\\"음성\\",\\"summary\\":\\"요약\\",\\"recommendation\\":\\"권장\\"}]}"
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 11,
                    "output_tokens": 22,
                    "total_tokens": 33
                  }
                }
                """;
    }

    private String completedResponseWithoutUsage() {
        return """
                {
                  "id": "resp_2",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "{\\"overall\\":\\"좋습니다.\\",\\"strengths\\":[\\"안정적입니다.\\"],\\"improvements\\":[\\"속도를 조절하세요.\\"],\\"practicePlan\\":[{\\"title\\":\\"연습\\",\\"description\\":\\"설명\\",\\"duration\\":\\"5분\\"}],\\"timelineFeedback\\":[{\\"category\\":\\"speech\\",\\"title\\":\\"음성\\",\\"summary\\":\\"요약\\",\\"recommendation\\":\\"권장\\"}]}"
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private record OpenAiClientFixture(
            OpenAiClient client,
            MockRestServiceServer server
    ) {
    }
}
