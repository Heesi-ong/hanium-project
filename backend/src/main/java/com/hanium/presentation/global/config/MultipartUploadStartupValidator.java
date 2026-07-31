package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
public class MultipartUploadStartupValidator {

    private static final long MINIMUM_MULTIPART_OVERHEAD_BYTES =
            DataSize.ofMegabytes(1).toBytes();

    private final DataSize maxFileSize;
    private final DataSize maxRequestSize;

    public MultipartUploadStartupValidator(
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size}") DataSize maxRequestSize
    ) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
    }

    @PostConstruct
    public void validate() {
        if (maxFileSize == null || maxFileSize.toBytes() < 1) {
            throw new IllegalStateException(
                    "spring.servlet.multipart.max-file-size는 1바이트 이상이어야 합니다."
            );
        }
        if (maxRequestSize == null || maxRequestSize.toBytes() < 1) {
            throw new IllegalStateException(
                    "spring.servlet.multipart.max-request-size는 1바이트 이상이어야 합니다."
            );
        }

        long multipartMargin = maxRequestSize.toBytes() - maxFileSize.toBytes();
        if (multipartMargin < MINIMUM_MULTIPART_OVERHEAD_BYTES) {
            throw new IllegalStateException(
                    "spring.servlet.multipart.max-request-size는 "
                            + "spring.servlet.multipart.max-file-size보다 multipart 헤더 여유 "
                            + "1MiB 이상 커야 합니다."
            );
        }
    }
}
