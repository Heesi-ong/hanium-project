package com.hanium.presentation.infrastructure.client;

import com.hanium.presentation.global.logging.RequestIdFilter;
import com.hanium.presentation.global.properties.EngineProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

// AnalysisEngineClient와 VideoLlmEngineClient가 거의 동일하게 구현하던 health/readiness
// 체크, readiness 메트릭 집계, requestId 전파 로직을 한 곳으로 모았습니다. analyze()는
// 요청/응답 DTO, URI, ErrorCode, 서킷브레이커 설정이 엔진마다 달라 하위 클래스에 남겨둡니다.
public abstract class AbstractEngineClient {

    protected static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    protected final RestClient restClient;
    protected final EngineProperties properties;

    private final Map<String, Counter> readinessCounters;
    private final String engineDisplayName;

    protected AbstractEngineClient(
            RestClient.Builder restClientBuilder,
            EngineProperties properties,
            MeterRegistry meterRegistry,
            String engineMetricTag,
            String engineDisplayName
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .build();
        this.readinessCounters = createReadinessCounters(meterRegistry, engineMetricTag);
        this.engineDisplayName = engineDisplayName;
    }

    private static Map<String, Counter> createReadinessCounters(MeterRegistry meterRegistry, String engineMetricTag) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String outcome : new String[] {"ready", "not_ready", "unauthenticated", "unreachable"}) {
            counters.put(
                    outcome,
                    Counter.builder("engine.readiness.check")
                            .description("엔진 authenticated readiness 조회 결과")
                            .tag("engine", engineMetricTag)
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
                    "message", e.getMessage() == null ? engineDisplayName + "에 연결할 수 없습니다." : e.getMessage()
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
                    "message", engineDisplayName + " 내부 API 키 인증에 실패했습니다."
            );
        } catch (Exception e) {
            recordReadinessOutcome("unreachable");
            return Map.of(
                    "reachable", false,
                    "authenticated", false,
                    "ready", false,
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? engineDisplayName + "에 연결할 수 없습니다." : e.getMessage()
            );
        }
    }

    // analyze() 하위 구현체가 요청 본문에 requestId 헤더를 실어 보낼 때 재사용하는 헬퍼입니다.
    // 엔진 로그에서도 같은 값으로 추적할 수 있도록, 이 호출을 시작한 backend HTTP 요청의
    // requestId를 그대로 전달합니다. 값이 없으면(예: 요청 맥락 밖 호출) 헤더를 생략할 뿐
    // 호출 자체는 그대로 진행합니다.
    protected RestClient.RequestBodySpec withRequestIdHeader(RestClient.RequestBodySpec requestSpec) {
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            return requestSpec.header(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
        return requestSpec;
    }

    public String getBaseUrl() {
        return properties.baseUrl();
    }
}
