package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "video")
public record VideoProperties(
        Long maxDurationMinutes
) {
}
