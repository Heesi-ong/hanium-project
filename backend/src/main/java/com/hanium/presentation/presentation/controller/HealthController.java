package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
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
    private final OpenAiProperties openAiProperties;
    private final FeedbackLlmProperties feedbackLlmProperties;
    private final boolean passwordResetEnabled;
    private final boolean smtpConfigured;

    public HealthController(
            AnalysisEngineClient analysisEngineClient,
            VideoLlmEngineClient videoLlmEngineClient,
            OpenAiProperties openAiProperties,
            FeedbackLlmProperties feedbackLlmProperties,
            @Value("${password-reset.enabled:true}") boolean passwordResetEnabled,
            @Value("${spring.mail.host:}") String smtpHost
    ) {
        this.analysisEngineClient = analysisEngineClient;
        this.videoLlmEngineClient = videoLlmEngineClient;
        this.openAiProperties = openAiProperties;
        this.feedbackLlmProperties = feedbackLlmProperties;
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
        ComponentStatus videoLlmEngine = videoLlmStatus(videoLlmEngineClient.checkReadiness());
        ComponentStatus aiFeedback = aiFeedbackStatus();
        ComponentStatus passwordReset = passwordResetStatus();

        Availability overallStatus = analysisEngine.status() == Availability.AVAILABLE
                && videoLlmEngine.status() == Availability.AVAILABLE
                && aiFeedback.status() == Availability.AVAILABLE
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
                        aiFeedback,
                        passwordReset
                )
        );
    }

    // feedback.llm.provider가 openai(기본)면 OpenAI API key를, nvidia면 NVIDIA API key를
    // 기준으로 판단한다. 외부 벤더 API는 상태 조회마다 실제로 호출하면 비용이 들고 무의미한
    // 트래픽이 쌓이므로, 두 엔진(analysisEngine/videoLlmEngine)과 달리 라이브 reachability가
    // 아니라 설정 상태(활성화 여부 + key 존재)만으로 판단한다.
    private ComponentStatus aiFeedbackStatus() {
        boolean configured = feedbackLlmProperties.isNvidiaProvider()
                ? feedbackLlmProperties.hasNvidiaApiKey()
                : openAiProperties.canUseRealApi();

        if (!configured) {
            return new ComponentStatus(
                    Availability.UNAVAILABLE,
                    "현재 AI 피드백 생성 기능을 이용할 수 없습니다."
            );
        }

        return new ComponentStatus(
                Availability.AVAILABLE,
                "AI 피드백 생성 기능을 정상적으로 이용할 수 있습니다."
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

    /**
     * Video LLM 엔진의 HTTP readiness와 사용자에게 제공할 실제 모델 capability를 구분합니다.
     * DISABLED 정책의 엔진도 MOCK 응답을 만들 수 있어 ready=true를 반환하지만, 이를 사용자에게
     * "Video LLM 사용 가능"으로 노출하면 mock 결과를 실제 분석으로 오인하게 됩니다.
     */
    private ComponentStatus videoLlmStatus(Map<String, Object> readiness) {
        ComponentStatus runtimeStatus = engineStatus(
                readiness,
                "Video LLM 분석 기능을 정상적으로 이용할 수 있습니다.",
                "Video LLM 분석 기능 일부가 제한되어 있습니다.",
                "현재 Video LLM 분석 기능을 이용할 수 없습니다."
        );

        if (runtimeStatus.status() != Availability.AVAILABLE) {
            return runtimeStatus;
        }

        Object rawResponse = readiness.get("response");
        if (!(rawResponse instanceof Map<?, ?> response)
                || !Boolean.TRUE.equals(response.get("realModelReady"))) {
            return new ComponentStatus(
                    Availability.UNAVAILABLE,
                    "현재 실제 Video LLM 분석 기능을 이용할 수 없습니다."
            );
        }

        return runtimeStatus;
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
