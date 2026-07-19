package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external.video-llm-engine")
public record VideoLlmEngineProperties(
        String baseUrl,
        String apiKey
) implements EngineProperties {
}
