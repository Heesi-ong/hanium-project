package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.presentation.dto.response.ServiceStatusResponse;
import com.hanium.presentation.presentation.dto.response.ServiceStatusResponse.Availability;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void publicHealthCheckContainsOnlyMinimalLivenessData() {
        HealthController healthController = controller(true, "smtp.example.com");

        ApiResponse<Map<String, String>> response = healthController.healthCheck();

        assertThat(response.data()).containsOnly(
                Map.entry("service", "backend"),
                Map.entry("status", "ok")
        );
    }

    @Test
    void serviceStatusMapsEngineDetailsToUserSafeAvailability() {
        AnalysisEngineClient analysisEngineClient = mock(AnalysisEngineClient.class);
        VideoLlmEngineClient videoLlmEngineClient = mock(VideoLlmEngineClient.class);
        HealthController healthController = new HealthController(
                analysisEngineClient,
                videoLlmEngineClient,
                true,
                "smtp.example.com"
        );

        when(analysisEngineClient.checkReadiness()).thenReturn(Map.of(
                "reachable", true,
                "authenticated", true,
                "ready", false,
                "response", Map.of(
                        "models", Map.of("pose", false),
                        "reason", "Analysis models are not loaded: pose"
                )
        ));
        when(videoLlmEngineClient.checkReadiness()).thenReturn(Map.of(
                "reachable", true,
                "authenticated", false,
                "ready", false,
                "message", "NVIDIA_API_KEY is missing"
        ));

        ApiResponse<ServiceStatusResponse> response = healthController.serviceStatus();

        assertThat(response.data().overallStatus()).isEqualTo(Availability.DEGRADED);
        assertThat(response.data().backend().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(response.data().analysisEngine().status()).isEqualTo(Availability.DEGRADED);
        assertThat(response.data().videoLlmEngine().status()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(response.data().passwordReset().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(response.data().toString())
                .doesNotContain("NVIDIA_API_KEY")
                .doesNotContain("models are not loaded")
                .doesNotContain("baseUrl")
                .doesNotContain("authenticated");
    }

    @Test
    void serviceStatusShowsPasswordResetWithoutExposingConfiguration() {
        HealthController healthController = controller(true, "");

        ApiResponse<ServiceStatusResponse> response = healthController.serviceStatus();

        assertThat(response.data().passwordReset().status()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(response.data().passwordReset().message())
                .isEqualTo("현재 비밀번호 재설정 이메일을 보낼 수 없습니다.")
                .doesNotContain("SMTP");
    }

    private HealthController controller(boolean passwordResetEnabled, String smtpHost) {
        AnalysisEngineClient analysisEngineClient = mock(AnalysisEngineClient.class);
        VideoLlmEngineClient videoLlmEngineClient = mock(VideoLlmEngineClient.class);
        when(analysisEngineClient.checkReadiness()).thenReturn(Map.of(
                "reachable", true,
                "authenticated", true,
                "ready", true
        ));
        when(videoLlmEngineClient.checkReadiness()).thenReturn(Map.of(
                "reachable", true,
                "authenticated", true,
                "ready", true
        ));
        return new HealthController(
                analysisEngineClient,
                videoLlmEngineClient,
                passwordResetEnabled,
                smtpHost
        );
    }
}
