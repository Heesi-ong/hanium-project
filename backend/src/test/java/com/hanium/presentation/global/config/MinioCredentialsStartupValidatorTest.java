package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.MinioProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioCredentialsStartupValidatorTest {

    @Test
    void rejectsInsecureDefaultAccessKey() {
        MinioCredentialsStartupValidator validator = new MinioCredentialsStartupValidator(
                new MinioProperties(
                        "http://localhost:9000",
                        "http://localhost:9000",
                        "minioadmin",
                        "a-real-strong-secret-key",
                        "hanium-storage"
                )
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder 기본값");
    }

    @Test
    void rejectsInsecureDefaultSecretKey() {
        MinioCredentialsStartupValidator validator = new MinioCredentialsStartupValidator(
                new MinioProperties(
                        "http://localhost:9000",
                        "http://localhost:9000",
                        "a-real-strong-access-key",
                        "minioadmin123",
                        "hanium-storage"
                )
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder 기본값");
    }

    @Test
    void acceptsRealCredentialValues() {
        MinioCredentialsStartupValidator validator = new MinioCredentialsStartupValidator(
                new MinioProperties(
                        "http://localhost:9000",
                        "http://localhost:9000",
                        "a-real-strong-access-key",
                        "a-real-strong-secret-key",
                        "hanium-storage"
                )
        );

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }
}
