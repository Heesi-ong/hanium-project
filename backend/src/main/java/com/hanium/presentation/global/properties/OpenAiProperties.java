package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external.openai")
public record OpenAiProperties(
        String apiKey,
        String model
) {
}