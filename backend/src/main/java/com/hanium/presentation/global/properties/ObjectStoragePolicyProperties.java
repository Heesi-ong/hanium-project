package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage.object-storage")
public record ObjectStoragePolicyProperties(
        boolean writeRequired,
        boolean readPreferred
) {
}
