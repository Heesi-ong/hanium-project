package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.api-docs")
public record ApiDocsProperties(boolean publicEnabled) {
}
