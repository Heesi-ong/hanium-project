package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.CoachLlmProperties;
import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 기동 시 외부 AI(피드백/코치 LLM)가 실제 호출 모드인지 mock 모드인지 한 줄로 남깁니다.
 *
 * <p>기본값(OPENAI_ENABLED=false)으로 배포하면 피드백·코치 응답이 전부 mock인데도 그 상태를
 * 알리는 로그가 없었습니다. {@code OpenAiApiKeyStartupValidator}는 {@code enabled && 키 없음}인
 * 잘못된 조합만 막고 정상 mock 상태는 침묵합니다. 운영자가 "이 인스턴스가 실제 외부 호출을
 * 하는가"를 부팅 로그만으로 확인할 수 있도록 {@code EXTERNAL_AI_MODE} 배너를 남깁니다.</p>
 *
 * <p>Video LLM 엔진의 mock/real 여부는 backend 설정이 아니라 엔진 자체 환경변수
 * (VIDEO_LLM_ENABLED/POLICY/BACKEND)가 결정하므로, backend는 응답의 {@code generationMode}로만
 * 알 수 있습니다. 여기서는 "엔진이 응답에 보고"한다는 사실만 표기합니다.</p>
 */
@Component
public class ExternalAiModeStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(ExternalAiModeStartupLogger.class);

    private final OpenAiProperties openAiProperties;
    private final FeedbackLlmProperties feedbackLlmProperties;
    private final CoachLlmProperties coachLlmProperties;

    public ExternalAiModeStartupLogger(
            OpenAiProperties openAiProperties,
            FeedbackLlmProperties feedbackLlmProperties,
            CoachLlmProperties coachLlmProperties
    ) {
        this.openAiProperties = openAiProperties;
        this.feedbackLlmProperties = feedbackLlmProperties;
        this.coachLlmProperties = coachLlmProperties;
    }

    @PostConstruct
    public void logExternalAiMode() {
        log.info(
                "EXTERNAL_AI_MODE openaiEnabled={} openaiApiKey={} feedback={}(provider={}) coach={}(provider={}) "
                        + "videoLlm=engine-reported(응답 generationMode 참고)",
                openAiProperties.isEnabled(),
                openAiProperties.hasApiKey() ? "present" : "empty",
                feedbackRealCallPossible() ? "real" : "mock",
                feedbackLlmProperties.isNvidiaProvider() ? "nvidia" : "openai",
                coachRealCallPossible() ? "real" : "mock",
                coachLlmProperties.isNvidiaProvider() ? "nvidia" : "openai"
        );
    }

    // OpenAiClient.canUseRealApi() / OpenAiCoachClient.canUseRealApi()와 동일한 판정입니다.
    private boolean feedbackRealCallPossible() {
        return feedbackLlmProperties.isNvidiaProvider()
                ? feedbackLlmProperties.hasNvidiaApiKey()
                : openAiProperties.canUseRealApi();
    }

    private boolean coachRealCallPossible() {
        return coachLlmProperties.isNvidiaProvider()
                ? coachLlmProperties.hasNvidiaApiKey()
                : openAiProperties.canUseRealApi();
    }
}
