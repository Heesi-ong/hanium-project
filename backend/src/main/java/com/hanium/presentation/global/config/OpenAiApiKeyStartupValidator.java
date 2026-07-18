package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.OpenAiProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// openai.enabled=true인데 OPENAI_API_KEY가 비어 있으면 OpenAiClient/OpenAiCoachClient는
// 예외 없이 조용히 mock으로 폴백한다(OpenAiProperties.canUseRealApi() 참고). 배포는 성공한
// 것처럼 보이지만 실제 AI 피드백 기능은 항상 mock으로만 동작하는 상태를 배포 직후 알아채기
// 어려우므로, InternalEngineApiKeyStartupValidator와 같은 이유로 기동 시점에 미리 걸러낸다.
@Component
@Profile({"dev", "prod"})
public class OpenAiApiKeyStartupValidator {

    private final boolean enabled;
    private final boolean hasApiKey;

    public OpenAiApiKeyStartupValidator(OpenAiProperties openAiProperties) {
        this.enabled = openAiProperties.isEnabled();
        this.hasApiKey = openAiProperties.hasApiKey();
    }

    @PostConstruct
    public void validate() {
        if (enabled && !hasApiKey) {
            throw new IllegalStateException(
                    "OPENAI_ENABLED=true인데 OPENAI_API_KEY가 비어 있습니다. dev/prod 환경에서는 "
                            + "실제 AI 피드백 기능이 조용히 mock으로 폴백되는 상태를 방지하기 위해 "
                            + "OPENAI_API_KEY를 반드시 설정하거나 OPENAI_ENABLED를 false로 두어야 합니다."
            );
        }
    }
}
