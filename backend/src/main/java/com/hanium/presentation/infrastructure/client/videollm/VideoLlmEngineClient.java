package com.hanium.presentation.infrastructure.client.videollm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import com.hanium.presentation.infrastructure.client.AbstractEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VideoLlmEngineClient extends AbstractEngineClient {

    private static final Logger log = LoggerFactory.getLogger(VideoLlmEngineClient.class);

    // generationMode(REAL/FALLBACK/MOCK)별 응답 건수 카운터. FALLBACK/MOCK 비율이 조용히
    // 올라가면 "실제 모델은 꺼져 있는데 사용자에겐 결과가 나가는" 품질 저하를 뜻하므로 감시합니다.
    private final Map<String, Counter> generationModeCounters;
    private final ObjectMapper objectMapper;

    public VideoLlmEngineClient(
            RestClient.Builder restClientBuilder,
            VideoLlmEngineProperties properties,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper
    ) {
        super(restClientBuilder, properties, meterRegistry, "video_llm", "Video LLM 엔진");
        this.objectMapper = objectMapper;

        this.generationModeCounters = new LinkedHashMap<>();
        for (String mode : new String[] {"REAL", "FALLBACK", "MOCK", "UNKNOWN"}) {
            this.generationModeCounters.put(
                    mode,
                    Counter.builder("video_llm.generation")
                            .description("Video LLM 응답을 생성 방식(generationMode)별로 집계한 건수")
                            .tag("mode", mode)
                            .register(meterRegistry)
            );
        }
    }

    private void recordGenerationMode(VideoLlmEngineResponse response) {
        String mode = "UNKNOWN";
        if (response != null && response.model() != null) {
            Object rawMode = response.model().get("generationMode");
            if (rawMode != null && generationModeCounters.containsKey(rawMode.toString())) {
                mode = rawMode.toString();
            }
        }
        generationModeCounters.get(mode).increment();
    }

    @CircuitBreaker(name = "video-llm-engine", fallbackMethod = "analyzeFallback")
    public VideoLlmEngineResponse analyze(VideoLlmEngineRequest request) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/api/video-llm/analyze")
                    .header(INTERNAL_API_KEY_HEADER, properties.apiKey() == null ? "" : properties.apiKey());

            requestSpec = withRequestIdHeader(requestSpec);

            VideoLlmEngineResponse response = requestSpec
                    .body(request)
                    .retrieve()
                    .body(VideoLlmEngineResponse.class);
            recordGenerationMode(response);
            return response;
        } catch (Exception e) {
            log.error(
                    "video-llm-engine 호출 실패: exceptionType={} message={}",
                    e.getClass().getName(),
                    e.getMessage(),
                    e
            );
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_ENGINE_ERROR,
                    describeEngineFailure(e)
            );
        }
    }

    private VideoLlmEngineResponse analyzeFallback(
            VideoLlmEngineRequest request,
            Throwable throwable
    ) {
        if (throwable instanceof CallNotPermittedException) {
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_ENGINE_ERROR,
                    "Video LLM 엔진이 반복적으로 실패해 일시적으로 호출을 차단했습니다. 잠시 후 다시 시도해주세요."
            );
        }

        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        throw new BusinessException(
                ErrorCode.VIDEO_LLM_ENGINE_ERROR,
                describeEngineFailure(throwable)
        );
    }

    // 사용자 화면(결과 상세의 failReason)에 그대로 노출되는 메시지입니다. HTTP 상태 줄과
    // 중첩 JSON이 그대로 섞인 e.getMessage()를 직접 보여주면 "502 Bad Gateway: {...}"
    // 같은 원시 예외 텍스트가 사용자에게 노출되는 문제가 있었습니다(2026-09-01 실사용 중
    // 발견). 원본 예외/스택트레이스는 위 log.error에 그대로 남기고, 사용자에게는 엔진이
    // 반환한 JSON의 detail.message만 뽑아 보여주거나, 그마저 없으면 일반화된 안내 문구를
    // 보여줍니다.
    private String describeEngineFailure(Throwable throwable) {
        if (throwable instanceof RestClientResponseException responseException) {
            String upstreamMessage = extractUpstreamMessage(responseException.getResponseBodyAsString());
            if (upstreamMessage != null) {
                return "Video LLM 분석에 실패했습니다: " + upstreamMessage;
            }
            return "Video LLM 엔진이 오류를 반환했습니다(HTTP "
                    + responseException.getStatusCode().value()
                    + "). 잠시 후 다시 시도해주세요.";
        }
        return "Video LLM 엔진과 통신하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
    }

    private String extractUpstreamMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    responseBody,
                    new TypeReference<Map<String, Object>>() {
                    }
            );

            Object detail = parsed.get("detail");
            if (detail instanceof Map<?, ?> detailMap) {
                Object detailMessage = detailMap.get("message");
                if (detailMessage instanceof String detailMessageString && !detailMessageString.isBlank()) {
                    return detailMessageString;
                }
            }

            Object topLevelMessage = parsed.get("message");
            if (topLevelMessage instanceof String topLevelMessageString && !topLevelMessageString.isBlank()) {
                return topLevelMessageString;
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }
}
