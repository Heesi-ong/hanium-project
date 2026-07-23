package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// feedback.llm.provider=nvidia일 때만 실제로 쓰이는 Chat Completions 클라이언트입니다.
// provider=openai면 OpenAiClient는 기존 openAiRestClient(Responses API)를 그대로 씁니다.
@Configuration
public class FeedbackLlmClientConfig {

    private static final String OPENAI_CHAT_COMPLETIONS_BASE_URL = "https://api.openai.com/v1";

    @Bean
    public RestClient feedbackLlmRestClient(
            RestClient.Builder restClientBuilder,
            OpenAiProperties openAiProperties,
            FeedbackLlmProperties feedbackLlmProperties
    ) {
        boolean useNvidia = feedbackLlmProperties.isNvidiaProvider();
        int readTimeoutMs = useNvidia
                ? feedbackLlmProperties.getNvidiaTimeoutMs()
                : openAiProperties.getTimeoutMs();

        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        String baseUrl = useNvidia ? feedbackLlmProperties.getNvidiaBaseUrl() : OPENAI_CHAT_COMPLETIONS_BASE_URL;
        String apiKey = useNvidia ? feedbackLlmProperties.getNvidiaApiKey() : openAiProperties.getApiKey();

        RestClient.Builder builder = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        return builder.build();
    }
}
