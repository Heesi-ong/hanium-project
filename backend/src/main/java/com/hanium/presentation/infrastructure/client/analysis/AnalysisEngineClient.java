package com.hanium.presentation.infrastructure.client.analysis;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.infrastructure.client.AbstractEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AnalysisEngineClient extends AbstractEngineClient {

    public AnalysisEngineClient(
            RestClient.Builder restClientBuilder,
            AnalysisEngineProperties properties,
            MeterRegistry meterRegistry
    ) {
        super(restClientBuilder, properties, meterRegistry, "analysis", "분석 엔진");
    }

    @CircuitBreaker(name = "analysis-engine", fallbackMethod = "analyzeFallback")
    public AnalysisEngineResponse analyze(AnalysisEngineRequest request) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/api/basic-analysis")
                    .header(INTERNAL_API_KEY_HEADER, properties.apiKey() == null ? "" : properties.apiKey());

            requestSpec = withRequestIdHeader(requestSpec);

            return requestSpec
                    .body(request)
                    .retrieve()
                    .body(AnalysisEngineResponse.class);
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ENGINE_ERROR,
                    "기본 분석 엔진 호출에 실패했습니다: " + e.getMessage()
            );
        }
    }

    private AnalysisEngineResponse analyzeFallback(
            AnalysisEngineRequest request,
            Throwable throwable
    ) {
        if (throwable instanceof CallNotPermittedException) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ENGINE_ERROR,
                    "기본 분석 엔진이 반복적으로 실패해 일시적으로 호출을 차단했습니다. 잠시 후 다시 시도해주세요."
            );
        }

        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        throw new BusinessException(
                ErrorCode.ANALYSIS_ENGINE_ERROR,
                "기본 분석 엔진 호출에 실패했습니다: " + throwable.getMessage()
        );
    }
}
