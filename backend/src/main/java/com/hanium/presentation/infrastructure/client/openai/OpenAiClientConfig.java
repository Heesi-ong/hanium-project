package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.properties.OpenAiProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
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
        // openai.timeout-ms 설정값을 실제 요청 timeout으로 적용합니다.
        // (restClientBuilder는 prototype 빈이라 다른 클라이언트의 타임아웃 설정과 섞이지 않습니다.)
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withReadTimeout(Duration.ofMillis(openAiProperties.getTimeoutMs()));

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(settings);

        RestClient.Builder builder = restClientBuilder
                .baseUrl(OPENAI_BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json");

        if (openAiProperties.hasApiKey()) {
            builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + openAiProperties.getApiKey()
            );
        }

        return builder.build();
    }
}