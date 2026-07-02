package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external.analysis-engine")
public record AnalysisEngineProperties(
        String baseUrl,
        String apiKey
) {
}
