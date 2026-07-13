package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// analysis-engine/video-llm-engine은 X-Internal-Api-Key가 비어 있으면 항상 401로 요청을
// 거절합니다(각 엔진의 verify_internal_api_key, fail-closed). 하지만 이 값이 비어 있어도
// backend 프로세스 자체는 정상적으로 기동되고 헬스체크도 통과하므로, 실제 사용자가 분석을
// 실행하기 전까지는 문제를 알아채기 어렵습니다("애매하게 나중에 실패"). dev/prod 환경에서는
// 기동 시점에 미리 걸러 배포 직후 바로 문제를 드러냅니다.
@Component
@Profile({"dev", "prod"})
public class InternalEngineApiKeyStartupValidator {

    private final String analysisEngineApiKey;
    private final String videoLlmEngineApiKey;

    public InternalEngineApiKeyStartupValidator(
            AnalysisEngineProperties analysisEngineProperties,
            VideoLlmEngineProperties videoLlmEngineProperties
    ) {
        this.analysisEngineApiKey = analysisEngineProperties.apiKey();
        this.videoLlmEngineApiKey = videoLlmEngineProperties.apiKey();
    }

    @PostConstruct
    public void validate() {
        if (isBlank(analysisEngineApiKey) || isBlank(videoLlmEngineApiKey)) {
            throw new IllegalStateException(
                    "dev/prod 환경에서는 INTERNAL_ENGINE_API_KEY 환경변수를 반드시 설정해야 합니다. "
                            + "이 값이 비어 있으면 analysis-engine/video-llm-engine이 모든 분석 요청을 "
                            + "401로 거절해, 배포 자체는 성공한 것처럼 보이지만 실제 분석 실행은 항상 "
                            + "실패합니다. .env.example을 참고해 값을 채우세요."
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
