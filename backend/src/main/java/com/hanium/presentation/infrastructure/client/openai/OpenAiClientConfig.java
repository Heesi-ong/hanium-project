package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.properties.OpenAiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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
        return RestClientConfigSupport.build(
                restClientBuilder,
                openAiProperties.getTimeoutMs(),
                OPENAI_BASE_URL,
                openAiProperties.getApiKey()
        );
    }
}
