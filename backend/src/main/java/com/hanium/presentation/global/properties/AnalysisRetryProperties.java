package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "analysis.retry")
public record AnalysisRetryProperties(
        int maxCount
) {
}
