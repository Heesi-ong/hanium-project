package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.presentation.dto.response.ServiceStatusResponse;
import com.hanium.presentation.presentation.dto.response.ServiceStatusResponse.Availability;
import com.hanium.presentation.presentation.dto.response.ServiceStatusResponse.ComponentStatus;
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
    public ApiResponse<Map<String, String>> healthCheck() {
        return ApiResponse.success(
                "백엔드 서버가 정상적으로 실행 중입니다.",
                Map.of(
                        "service", "backend",
                        "status", "ok"
                )
        );
    }

    @GetMapping("/api/status")
    public ApiResponse<ServiceStatusResponse> serviceStatus() {
        ComponentStatus analysisEngine = engineStatus(
                analysisEngineClient.checkReadiness(),
                "기본 분석 기능을 정상적으로 이용할 수 있습니다.",
                "기본 분석 기능 일부가 준비되지 않았습니다.",
                "현재 기본 분석 기능을 이용할 수 없습니다."
        );
        ComponentStatus videoLlmEngine = engineStatus(
                videoLlmEngineClient.checkReadiness(),
                "Video LLM 분석 기능을 정상적으로 이용할 수 있습니다.",
                "Video LLM 분석 기능 일부가 제한되어 있습니다.",
                "현재 Video LLM 분석 기능을 이용할 수 없습니다."
        );
        ComponentStatus passwordReset = passwordResetStatus();

        Availability overallStatus = analysisEngine.status() == Availability.AVAILABLE
                && videoLlmEngine.status() == Availability.AVAILABLE
                && passwordReset.status() == Availability.AVAILABLE
                ? Availability.AVAILABLE
                : Availability.DEGRADED;

        return ApiResponse.success(
                "서비스 상태 조회가 완료되었습니다.",
                new ServiceStatusResponse(
                        overallStatus,
                        new ComponentStatus(
                                Availability.AVAILABLE,
                                "서비스에 정상적으로 연결되었습니다."
                        ),
                        analysisEngine,
                        videoLlmEngine,
                        passwordReset
                )
        );
    }

    private ComponentStatus engineStatus(
            Map<String, Object> readiness,
            String availableMessage,
            String degradedMessage,
            String unavailableMessage
    ) {
        if (!Boolean.TRUE.equals(readiness.get("reachable"))
                || !Boolean.TRUE.equals(readiness.get("authenticated"))) {
            return new ComponentStatus(Availability.UNAVAILABLE, unavailableMessage);
        }

        if (!Boolean.TRUE.equals(readiness.get("ready"))) {
            return new ComponentStatus(Availability.DEGRADED, degradedMessage);
        }

        return new ComponentStatus(Availability.AVAILABLE, availableMessage);
    }

    private ComponentStatus passwordResetStatus() {
        if (!passwordResetEnabled) {
            return new ComponentStatus(
                    Availability.DEGRADED,
                    "비밀번호 재설정 기능이 현재 제공되지 않습니다."
            );
        }

        if (!smtpConfigured) {
            return new ComponentStatus(
                    Availability.UNAVAILABLE,
                    "현재 비밀번호 재설정 이메일을 보낼 수 없습니다."
            );
        }

        return new ComponentStatus(
                Availability.AVAILABLE,
                "비밀번호 재설정 이메일을 정상적으로 이용할 수 있습니다."
        );
    }
}
