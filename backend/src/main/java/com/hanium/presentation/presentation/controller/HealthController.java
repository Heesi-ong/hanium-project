package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final AnalysisEngineClient analysisEngineClient;
    private final VideoLlmEngineClient videoLlmEngineClient;

    public HealthController(
            AnalysisEngineClient analysisEngineClient,
            VideoLlmEngineClient videoLlmEngineClient
    ) {
        this.analysisEngineClient = analysisEngineClient;
        this.videoLlmEngineClient = videoLlmEngineClient;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> healthCheck() {
        return ApiResponse.success(
                "백엔드 서버가 정상적으로 실행 중입니다.",
                Map.of(
                        "service", "backend",
                        "status", "ok"
                )
        );
    }

    @GetMapping("/api/health/engines")
    public ApiResponse<Map<String, Object>> engineHealthCheck() {
        Map<String, Object> analysisEngineHealth = analysisEngineClient.checkHealth();
        Map<String, Object> videoLlmEngineHealth = videoLlmEngineClient.checkHealth();

        return ApiResponse.success(
                "외부 엔진 상태 조회가 완료되었습니다.",
                Map.of(
                        "analysisEngine", Map.of(
                                "baseUrl", analysisEngineClient.getBaseUrl(),
                                "health", analysisEngineHealth
                        ),
                        "videoLlmEngine", Map.of(
                                "baseUrl", videoLlmEngineClient.getBaseUrl(),
                                "health", videoLlmEngineHealth
                        )
                )
        );
    }
}