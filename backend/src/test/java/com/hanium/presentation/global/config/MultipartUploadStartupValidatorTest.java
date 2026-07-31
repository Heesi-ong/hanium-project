package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MultipartUploadStartupValidatorTest {

    @Test
    void acceptsOneMebibyteMultipartOverheadMargin() {
        MultipartUploadStartupValidator validator = new MultipartUploadStartupValidator(
                DataSize.ofMegabytes(500),
                DataSize.ofMegabytes(501)
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsEqualFileAndRequestLimits() {
        MultipartUploadStartupValidator validator = new MultipartUploadStartupValidator(
                DataSize.ofMegabytes(500),
                DataSize.ofMegabytes(500)
        );

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("1MiB");
    }

    @Test
    void rejectsMarginSmallerThanOneMebibyte() {
        MultipartUploadStartupValidator validator = new MultipartUploadStartupValidator(
                DataSize.ofMegabytes(500),
                DataSize.ofBytes(
                        DataSize.ofMegabytes(500).toBytes()
                                + DataSize.ofKilobytes(512).toBytes()
                )
        );

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("multipart 헤더");
    }
}
