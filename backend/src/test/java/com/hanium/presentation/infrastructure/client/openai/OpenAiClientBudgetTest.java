package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiClientBudgetTest {

    @Test
    void generateFeedbackFallsBackToMockWithoutCallingOpenAiWhenMonthlyBudgetExceeded() {
        OpenAiProperties properties = createEnabledProperties();
        OpenAiPromptBuilder promptBuilder = mock(OpenAiPromptBuilder.class);
        RestClient restClient = mock(RestClient.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);
        when(userRateLimiter.tryConsume(eq("openai-monthly"), anyString())).thenReturn(false);

        FeedbackLlmProperties feedbackLlmProperties = new FeedbackLlmProperties();
        feedbackLlmProperties.setProvider("openai");

        OpenAiClient client = new OpenAiClient(
                properties,
                feedbackLlmProperties,
                promptBuilder,
                restClient,
                mock(RestClient.class),
                objectMapper,
                userRateLimiter
        );

        OpenAiFeedbackResponse response = client.generateFeedback(createRequest());

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).contains("budget");
        verify(userRateLimiter).tryConsume(eq("openai-monthly"), anyString());
        verifyNoInteractions(restClient);
    }

    @Test
    void generateFeedbackDoesNotConsumeMonthlyBudgetWhenOpenAiIsDisabled() {
        OpenAiProperties properties = createEnabledProperties();
        properties.setEnabled(false);
        OpenAiPromptBuilder promptBuilder = mock(OpenAiPromptBuilder.class);
        RestClient restClient = mock(RestClient.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);

        FeedbackLlmProperties feedbackLlmProperties = new FeedbackLlmProperties();
        feedbackLlmProperties.setProvider("openai");

        OpenAiClient client = new OpenAiClient(
                properties,
                feedbackLlmProperties,
                promptBuilder,
                restClient,
                mock(RestClient.class),
                objectMapper,
                userRateLimiter
        );

        OpenAiFeedbackResponse response = client.generateFeedback(createRequest());

        assertThat(response.generationMode()).isEqualTo("MOCK");
        assertThat(response.fallbackReason()).isEqualTo("openai.enabled=false");
        verifyNoInteractions(userRateLimiter, restClient);
    }

    private OpenAiProperties createEnabledProperties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-api-key");
        properties.setModel("gpt-test");
        properties.setTimeoutMs(15000);
        return properties;
    }

    private OpenAiFeedbackRequest createRequest() {
        return new OpenAiFeedbackRequest("budget-job", Map.of());
    }
}
