package com.hanium.presentation.infrastructure.client.openai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
                new OpenAiPromptBuilder(objectMapper),
                builder.build(),
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
                new OpenAiPromptBuilder(objectMapper),
                RestClient.builder().build(),
                objectMapper,
                mock(UserRateLimiter.class)
        );
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
                          "text": "{\\"overall\\":\\"좋습니다.\\",\\"strengths\\":[\\"안정적입니다.\\"],\\"improvements\\":[\\"속도를 조절하세요.\\"],\\"practicePlan\\":[],\\"timelineFeedback\\":[]}"
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
                          "text": "{\\"overall\\":\\"좋습니다.\\",\\"strengths\\":[\\"안정적입니다.\\"],\\"improvements\\":[\\"속도를 조절하세요.\\"],\\"practicePlan\\":[],\\"timelineFeedback\\":[]}"
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
