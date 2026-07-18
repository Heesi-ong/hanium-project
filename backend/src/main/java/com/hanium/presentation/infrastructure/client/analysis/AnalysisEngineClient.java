package com.hanium.presentation.infrastructure.client.analysis;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.logging.RequestIdFilter;
import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AnalysisEngineClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final AnalysisEngineProperties properties;
    private final Map<String, Counter> readinessCounters;

    public AnalysisEngineClient(
            RestClient.Builder restClientBuilder,
            AnalysisEngineProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .build();
        this.readinessCounters = createReadinessCounters(meterRegistry);
    }

    private static Map<String, Counter> createReadinessCounters(MeterRegistry meterRegistry) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String outcome : new String[] {"ready", "not_ready", "unauthenticated", "unreachable"}) {
            counters.put(
                    outcome,
                    Counter.builder("engine.readiness.check")
                            .description("엔진 authenticated readiness 조회 결과")
                            .tag("engine", "analysis")
                            .tag("outcome", outcome)
                            .register(meterRegistry)
            );
        }
        return counters;
    }

    private void recordReadinessOutcome(String outcome) {
        readinessCounters.get(outcome).increment();
    }

    public Map<String, Object> checkHealth() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);

            return Map.of(
                    "status", "up",
                    "reachable", true,
                    "response", response == null ? Map.of() : response
            );
        } catch (Exception e) {
            return Map.of(
                    "status", "down",
                    "reachable", false,
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? "분석 엔진에 연결할 수 없습니다." : e.getMessage()
            );
        }
    }

    // /health와 달리 X-Internal-Api-Key로 실제 분석 API 인증 경로까지 검증합니다.
    // reachable(연결 가능)과 authenticated(키 인증 통과)를 분리해 "떠 있지만 인증 실패"
    // 상태를 구분할 수 있게 합니다.
    public Map<String, Object> checkReadiness() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/internal/readiness")
                    .header(INTERNAL_API_KEY_HEADER, properties.apiKey() == null ? "" : properties.apiKey())
                    .retrieve()
                    .body(Map.class);

            boolean ready = response != null && Boolean.TRUE.equals(response.get("ready"));
            recordReadinessOutcome(ready ? "ready" : "not_ready");

            return Map.of(
                    "reachable", true,
                    "authenticated", true,
                    "ready", ready,
                    "response", response == null ? Map.of() : response
            );
        } catch (HttpClientErrorException.Unauthorized e) {
            recordReadinessOutcome("unauthenticated");
            return Map.of(
                    "reachable", true,
                    "authenticated", false,
                    "ready", false,
                    "error", e.getClass().getSimpleName(),
                    "message", "분석 엔진 내부 API 키 인증에 실패했습니다."
            );
        } catch (Exception e) {
            recordReadinessOutcome("unreachable");
            return Map.of(
                    "reachable", false,
                    "authenticated", false,
                    "ready", false,
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? "분석 엔진에 연결할 수 없습니다." : e.getMessage()
            );
        }
    }

    @CircuitBreaker(name = "analysis-engine", fallbackMethod = "analyzeFallback")
    public AnalysisEngineResponse analyze(AnalysisEngineRequest request) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/api/basic-analysis")
                    .header(INTERNAL_API_KEY_HEADER, properties.apiKey() == null ? "" : properties.apiKey());

            // analysis-engine 로그에서도 같은 값으로 추적할 수 있도록, 이 호출을 시작한 backend
            // HTTP 요청의 requestId를 그대로 전달합니다. 비동기 파이프라인이 AsyncConfig에서
            // MDC를 복사해오므로 이 시점에도 값이 남아 있지만, 혹시 없더라도(예: 스케줄러 등
            // 요청 맥락 밖에서 호출) 헤더를 생략할 뿐 호출 자체는 그대로 진행합니다.
            String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
            if (requestId != null && !requestId.isBlank()) {
                requestSpec = requestSpec.header(RequestIdFilter.REQUEST_ID_HEADER, requestId);
            }

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

    public String getBaseUrl() {
        return properties.baseUrl();
    }
}
