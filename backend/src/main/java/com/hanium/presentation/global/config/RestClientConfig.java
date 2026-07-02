package com.hanium.presentation.global.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    // analysis-engine / video-llm-engine 호출이 응답 없이 무한정 대기해서 백엔드 스레드를
    // 붙잡아 두는 문제를 막기 위한 기본 타임아웃입니다. 영상 분석은 시간이 걸릴 수 있어
    // read timeout을 넉넉하게 잡았습니다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(10);

    // RestClient.Builder는 호출할 때마다 새로 만들어 쓰는 것을 전제로 한 가변 객체입니다.
    // AnalysisEngineClient / VideoLlmEngineClient / OpenAiClientConfig 세 곳이 하나의
    // 인스턴스를 공유하면 서로 설정(baseUrl, requestFactory 등)을 덮어쓸 위험이 있어서,
    // prototype 스코프로 두어 주입받을 때마다 새 인스턴스를 받도록 합니다.
    // (Spring Boot가 자동 구성하는 RestClient.Builder도 기본이 prototype입니다.)
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(settings);

        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}