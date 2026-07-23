package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void engineHealthCheckPreservesEngineReadinessDetails() {
        AnalysisEngineClient analysisEngineClient = mock(AnalysisEngineClient.class);
        VideoLlmEngineClient videoLlmEngineClient = mock(VideoLlmEngineClient.class);
        HealthController healthController = new HealthController(
                analysisEngineClient,
                videoLlmEngineClient,
                true,
                "smtp.example.com"
        );

        when(analysisEngineClient.getBaseUrl()).thenReturn("http://analysis-engine:8001");
        when(analysisEngineClient.checkHealth()).thenReturn(Map.of(
                "reachable", true,
                "status", "up"
        ));
        when(analysisEngineClient.checkReadiness()).thenReturn(Map.of(
                "reachable", true,
                "authenticated", true,
                "ready", false,
                "response", Map.of(
                        "models", Map.of(
                                "whisper", true,
                                "pose", false,
                                "face", true
                        ),
                        "reason", "Analysis models are not loaded: pose"
                )
        ));

        when(videoLlmEngineClient.getBaseUrl()).thenReturn("http://video-llm-engine:8002");
        when(videoLlmEngineClient.checkHealth()).thenReturn(Map.of(
                "reachable", true,
                "status", "up"
        ));
        when(videoLlmEngineClient.checkReadiness()).thenReturn(Map.of(
                "reachable", true,
                "authenticated", true,
                "ready", false,
                "response", Map.of(
                        "mode", "FALLBACK",
                        "realModelReady", false,
                        "reason", "VIDEO_LLM_ENABLED=true but NVIDIA_API_KEY is missing"
                )
        ));

        ApiResponse<Map<String, Object>> apiResponse = healthController.engineHealthCheck();

        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data())
                .extractingByKey("analysisEngine")
                .isInstanceOfSatisfying(Map.class, analysisEngine -> {
                    assertThat(analysisEngine.get("baseUrl")).isEqualTo("http://analysis-engine:8001");
                    assertThat(analysisEngine.get("readiness"))
                            .isInstanceOfSatisfying(Map.class, readiness -> {
                                assertThat(readiness.get("ready")).isEqualTo(false);
                                assertThat(readiness.get("response"))
                                        .isInstanceOfSatisfying(Map.class, response -> {
                                            assertThat(response.get("reason").toString())
                                                    .contains("Analysis models are not loaded: pose");
                                            assertThat(response.get("models"))
                                                    .isInstanceOfSatisfying(Map.class, models -> {
                                                        assertThat(models.get("whisper")).isEqualTo(true);
                                                        assertThat(models.get("pose")).isEqualTo(false);
                                                        assertThat(models.get("face")).isEqualTo(true);
                                                    });
                                        });
                            });
                });
        assertThat(apiResponse.data())
                .extractingByKey("videoLlmEngine")
                .isInstanceOfSatisfying(Map.class, videoLlmEngine -> {
                    assertThat(videoLlmEngine.get("baseUrl")).isEqualTo("http://video-llm-engine:8002");
                    assertThat(videoLlmEngine.get("readiness"))
                            .isInstanceOfSatisfying(Map.class, readiness -> {
                                assertThat(readiness.get("ready")).isEqualTo(false);
                                assertThat(readiness.get("response"))
                                        .isInstanceOfSatisfying(Map.class, response -> {
                                            assertThat(response.get("mode")).isEqualTo("FALLBACK");
                                            assertThat(response.get("realModelReady")).isEqualTo(false);
                                            assertThat(response.get("reason").toString())
                                                    .contains("NVIDIA_API_KEY is missing");
                                        });
                            });
                });
    }

    @Test
    void healthCheckExposesPasswordResetFeatureAndSmtpStatus() {
        HealthController enabledWithSmtpController = new HealthController(
                mock(AnalysisEngineClient.class),
                mock(VideoLlmEngineClient.class),
                true,
                "smtp.example.com"
        );

        ApiResponse<Map<String, Object>> enabledResponse = enabledWithSmtpController.healthCheck();

        assertThat(enabledResponse.data())
                .extractingByKey("passwordReset")
                .isInstanceOfSatisfying(Map.class, passwordReset -> {
                    assertThat(passwordReset.get("enabled")).isEqualTo(true);
                    assertThat(passwordReset.get("smtpConfigured")).isEqualTo(true);
                });

        HealthController disabledWithoutSmtpController = new HealthController(
                mock(AnalysisEngineClient.class),
                mock(VideoLlmEngineClient.class),
                false,
                ""
        );

        ApiResponse<Map<String, Object>> disabledResponse = disabledWithoutSmtpController.healthCheck();

        assertThat(disabledResponse.data())
                .extractingByKey("passwordReset")
                .isInstanceOfSatisfying(Map.class, passwordReset -> {
                    assertThat(passwordReset.get("enabled")).isEqualTo(false);
                    assertThat(passwordReset.get("smtpConfigured")).isEqualTo(false);
                });
    }
}
