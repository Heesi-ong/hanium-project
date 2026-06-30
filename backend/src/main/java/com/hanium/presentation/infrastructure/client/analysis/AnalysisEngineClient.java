package com.hanium.presentation.infrastructure.client.analysis;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class AnalysisEngineClient {

    private final RestClient restClient;
    private final AnalysisEngineProperties properties;

    public AnalysisEngineClient(
            RestClient.Builder restClientBuilder,
            AnalysisEngineProperties properties
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
                    "message", e.getMessage() == null ? "분석 엔진에 연결할 수 없습니다." : e.getMessage()
            );
        }
    }

    public AnalysisEngineResponse analyze(AnalysisEngineRequest request) {
        try {
            return restClient.post()
                    .uri("/api/basic-analysis")
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

    public String getBaseUrl() {
        return properties.baseUrl();
    }
}