package com.hanium.presentation.infrastructure.client.openai;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// OpenAiClientConfig/CoachLlmClientConfig/FeedbackLlmClientConfig가 공통으로 필요한
// RestClient 조립(timeout 적용 requestFactory + baseUrl + Bearer 헤더)만 모아둔
// 헬퍼입니다. base URL/timeout/API 키를 무엇으로 정할지는 각 설정 클래스의 책임입니다.
final class RestClientConfigSupport {

    private RestClientConfigSupport() {
    }

    static RestClient build(
            RestClient.Builder restClientBuilder,
            int readTimeoutMs,
            String baseUrl,
            String apiKey
    ) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

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
