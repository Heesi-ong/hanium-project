package com.hanium.presentation.global.properties;

// AnalysisEngineProperties와 VideoLlmEngineProperties가 공통으로 구현해,
// AbstractEngineClient가 구체 타입에 의존하지 않고 baseUrl/apiKey에 접근할 수 있게 합니다.
public interface EngineProperties {
    String baseUrl();
    String apiKey();
}
