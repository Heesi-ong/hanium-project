package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final AnalysisEngineClient analysisEngineClient;
    private final VideoLlmEngineClient videoLlmEngineClient;
    private final boolean passwordResetEnabled;
    private final boolean smtpConfigured;

    public HealthController(
            AnalysisEngineClient analysisEngineClient,
            VideoLlmEngineClient videoLlmEngineClient,
            @Value("${password-reset.enabled:true}") boolean passwordResetEnabled,
            @Value("${spring.mail.host:}") String smtpHost
    ) {
        this.analysisEngineClient = analysisEngineClient;
        this.videoLlmEngineClient = videoLlmEngineClient;
        this.passwordResetEnabled = passwordResetEnabled;
        this.smtpConfigured = smtpHost != null && !smtpHost.isBlank();
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> healthCheck() {
        return ApiResponse.success(
                "백엔드 서버가 정상적으로 실행 중입니다.",
                Map.of(
                        "service", "backend",
                        "status", "ok",
                        // 운영자가 "서비스는 정상"과 "비밀번호 재설정 이메일이 실제로 나가는지"를
                        // 별도로 확인할 수 있도록 노출합니다(2026-07-23 코드 리뷰 P1-02).
                        "passwordReset", Map.of(
                                "enabled", passwordResetEnabled,
                                "smtpConfigured", smtpConfigured
                        )
                )
        );
    }

    @GetMapping("/api/health/engines")
    public ApiResponse<Map<String, Object>> engineHealthCheck() {
        Map<String, Object> analysisEngineHealth = analysisEngineClient.checkHealth();
        Map<String, Object> analysisEngineReadiness = analysisEngineClient.checkReadiness();
        Map<String, Object> videoLlmEngineHealth = videoLlmEngineClient.checkHealth();
        Map<String, Object> videoLlmEngineReadiness = videoLlmEngineClient.checkReadiness();

        return ApiResponse.success(
                "외부 엔진 상태 조회가 완료되었습니다.",
                Map.of(
                        "analysisEngine", Map.of(
                                "baseUrl", analysisEngineClient.getBaseUrl(),
                                "health", analysisEngineHealth,
                                "readiness", analysisEngineReadiness
                        ),
                        "videoLlmEngine", Map.of(
                                "baseUrl", videoLlmEngineClient.getBaseUrl(),
                                "health", videoLlmEngineHealth,
                                "readiness", videoLlmEngineReadiness
                        )
                )
        );
    }
}