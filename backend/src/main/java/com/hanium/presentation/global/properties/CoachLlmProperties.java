package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// AI 코치 채팅이 호출할 LLM을 openai(실서비스용, 기본값)와 nvidia(build.nvidia.com의
// 무료/오픈 모델, OpenAI 키를 아직 받지 못했을 때의 임시 대체) 중에서 고릅니다. 두 provider
// 모두 표준 Chat Completions 형식(POST /chat/completions)을 쓰므로, 나중에 실제 OpenAI 키가
// 준비되면 코드 변경 없이 provider 설정값만 openai로 되돌리면 됩니다.
@Component
@ConfigurationProperties(prefix = "coach.llm")
public class CoachLlmProperties {

    private String provider = "openai";
    private String nvidiaApiKey;
    private String nvidiaBaseUrl;
    private String nvidiaModel;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getNvidiaApiKey() {
        return nvidiaApiKey;
    }

    public void setNvidiaApiKey(String nvidiaApiKey) {
        this.nvidiaApiKey = nvidiaApiKey;
    }

    public String getNvidiaBaseUrl() {
        return nvidiaBaseUrl;
    }

    public void setNvidiaBaseUrl(String nvidiaBaseUrl) {
        this.nvidiaBaseUrl = nvidiaBaseUrl;
    }

    public String getNvidiaModel() {
        return nvidiaModel;
    }

    public void setNvidiaModel(String nvidiaModel) {
        this.nvidiaModel = nvidiaModel;
    }

    public boolean isNvidiaProvider() {
        return "nvidia".equalsIgnoreCase(provider);
    }

    public boolean hasNvidiaApiKey() {
        return nvidiaApiKey != null && !nvidiaApiKey.isBlank();
    }
}
