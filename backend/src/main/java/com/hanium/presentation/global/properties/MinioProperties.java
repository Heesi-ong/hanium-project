package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucketName
) {
}
