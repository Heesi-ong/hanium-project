package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.properties.OpenAiProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class OpenAiClientConfig {

    private static final String OPENAI_BASE_URL = "https://api.openai.com";

    @Bean
    public RestClient openAiRestClient(
            RestClient.Builder restClientBuilder,
            OpenAiProperties openAiProperties
    ) {
        RestClient.Builder builder = restClientBuilder
                .baseUrl(OPENAI_BASE_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json");

        if (openAiProperties.hasApiKey()) {
            builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + openAiProperties.getApiKey()
            );
        }

        return builder.build();
    }

    @Bean
    public RestClientCustomizer openAiRestClientTimeoutCustomizer(
            OpenAiProperties openAiProperties
    ) {
        return restClientBuilder -> {
            // Spring Boot의 기본 RestClient.Builder를 그대로 사용합니다.
            // 실제 요청 timeout 세부 제어가 필요하면 다음 단계에서
            // ClientHttpRequestFactory 기반 설정으로 확장합니다.
            Duration.ofMillis(openAiProperties.getTimeoutMs());
        };
    }
}