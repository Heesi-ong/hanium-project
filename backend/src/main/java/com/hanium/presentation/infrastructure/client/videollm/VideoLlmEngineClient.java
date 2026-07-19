package com.hanium.presentation.infrastructure.client.videollm;

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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VideoLlmEngineClient extends AbstractEngineClient {

    // generationMode(REAL/FALLBACK/MOCK)별 응답 건수 카운터. FALLBACK/MOCK 비율이 조용히
    // 올라가면 "실제 모델은 꺼져 있는데 사용자에겐 결과가 나가는" 품질 저하를 뜻하므로 감시합니다.
    private final Map<String, Counter> generationModeCounters;

    public VideoLlmEngineClient(
            RestClient.Builder restClientBuilder,
            VideoLlmEngineProperties properties,
            MeterRegistry meterRegistry
    ) {
        super(restClientBuilder, properties, meterRegistry, "video_llm", "Video LLM 엔진");

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
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_ENGINE_ERROR,
                    "Video LLM 엔진 호출에 실패했습니다: " + e.getMessage()
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
                "Video LLM 엔진 호출에 실패했습니다: " + throwable.getMessage()
        );
    }
}
