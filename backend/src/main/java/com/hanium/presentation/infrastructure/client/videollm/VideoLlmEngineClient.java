package com.hanium.presentation.infrastructure.client.videollm;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class VideoLlmEngineClient {

    private final RestClient restClient;
    private final VideoLlmEngineProperties properties;

    public VideoLlmEngineClient(
            RestClient.Builder restClientBuilder,
            VideoLlmEngineProperties properties
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .build();
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
                    "message", e.getMessage() == null ? "Video LLM 엔진에 연결할 수 없습니다." : e.getMessage()
            );
        }
    }

    public VideoLlmEngineResponse analyze(VideoLlmEngineRequest request) {
        try {
            return restClient.post()
                    .uri("/api/video-llm/analyze")
                    .body(request)
                    .retrieve()
                    .body(VideoLlmEngineResponse.class);
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_ENGINE_ERROR,
                    "Video LLM 엔진 호출에 실패했습니다: " + e.getMessage()
            );
        }
    }

    public String getBaseUrl() {
        return properties.baseUrl();
    }
}