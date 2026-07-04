package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        String rootPath,
        String uploadPath,
        String resultPath,
        String tempPath,
        String logPath,
        Long minFreeSpaceMb
) {
}