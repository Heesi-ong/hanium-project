package com.hanium.presentation.global.config;

import org.springframework.beans.factory.annotation.Value;
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

    // RestClient.Builder는 호출할 때마다 새로 만들어 쓰는 것을 전제로 한 가변 객체입니다.
    // AnalysisEngineClient / VideoLlmEngineClient / OpenAiClientConfig 세 곳이 하나의
    // 인스턴스를 공유하면 서로 설정(baseUrl, requestFactory 등)을 덮어쓸 위험이 있어서,
    // prototype 스코프로 두어 주입받을 때마다 새 인스턴스를 받도록 합니다.
    // (Spring Boot가 자동 구성하는 RestClient.Builder도 기본이 prototype입니다.)
    //
    // connect/read 타임아웃을 설정값으로 노출했습니다. read 타임아웃은 한 번의 외부 호출이
    // 최대 얼마나 매달릴 수 있는지를 정하며, 곧 "긴 호출 도중 취소/타임아웃이 최대 얼마 만에
    // 반영되는지"의 상한이 됩니다. 기본값은 기존과 동일(5초 / 10분)입니다.
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder(
            @Value("${external.http.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${external.http.read-timeout-minutes:10}") long readTimeoutMinutes
    ) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .withReadTimeout(Duration.ofMinutes(readTimeoutMinutes));

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(settings);

        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}