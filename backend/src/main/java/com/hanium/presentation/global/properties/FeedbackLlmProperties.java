package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// 분석 피드백(강점/개선점/연습 계획) 생성이 호출할 LLM을 openai(실서비스용, 기본값)와
// nvidia(build.nvidia.com의 무료/오픈 모델, OpenAI 키를 아직 받지 못했을 때의 임시 대체)
// 중에서 고릅니다. openai는 기존처럼 Responses API의 strict json_schema로 필드 형식을
// 보장받지만, nvidia는 이를 지원하지 않는 모델이 많아 Chat Completions의 json_object
// 모드(문법적으로 유효한 JSON만 보장, 필드 스키마는 프롬프트 지시로 유도)를 씁니다.
@Component
@ConfigurationProperties(prefix = "feedback.llm")
public class FeedbackLlmProperties {

    private String provider = "openai";
    private String nvidiaApiKey;
    private String nvidiaBaseUrl;
    private String nvidiaModel;
    // 최종 피드백은 프롬프트가 크고(전체 compactAnalysis) 응답도 최대 2000 토큰의 구조화
    // JSON이라, 코치 채팅용 openai.timeout-ms(기본 15초)보다 훨씬 여유가 필요합니다.
    // 실측 결과 45초도 부족해(2026-07-23, meta/llama-3.1-70b-instruct) 90초로 늘렸습니다.
    private int nvidiaTimeoutMs = 90000;

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

    public int getNvidiaTimeoutMs() {
        return nvidiaTimeoutMs;
    }

    public void setNvidiaTimeoutMs(int nvidiaTimeoutMs) {
        this.nvidiaTimeoutMs = nvidiaTimeoutMs;
    }

    public boolean isNvidiaProvider() {
        return "nvidia".equalsIgnoreCase(provider);
    }

    public boolean hasNvidiaApiKey() {
        return nvidiaApiKey != null && !nvidiaApiKey.isBlank();
    }
}
