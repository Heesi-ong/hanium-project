package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.properties.CoachLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CoachLlmClientConfig {

    // 기존 openAiRestClient(Responses API, /v1/responses)와 별개로, 코치 채팅 전용 Chat
    // Completions 클라이언트입니다. coach.llm.provider가 openai/nvidia 중 무엇이냐에 따라
    // 기동 시점에 base URL/키를 한 번 정해 빈에 고정합니다.
    private static final String OPENAI_CHAT_COMPLETIONS_BASE_URL = "https://api.openai.com/v1";

    @Bean
    public RestClient coachLlmRestClient(
            RestClient.Builder restClientBuilder,
            OpenAiProperties openAiProperties,
            CoachLlmProperties coachLlmProperties
    ) {
        boolean useNvidia = coachLlmProperties.isNvidiaProvider();
        String baseUrl = useNvidia ? coachLlmProperties.getNvidiaBaseUrl() : OPENAI_CHAT_COMPLETIONS_BASE_URL;
        String apiKey = useNvidia ? coachLlmProperties.getNvidiaApiKey() : openAiProperties.getApiKey();

        return RestClientConfigSupport.build(
                restClientBuilder,
                openAiProperties.getTimeoutMs(),
                baseUrl,
                apiKey
        );
    }
}
