package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretStartupValidatorTest {

    @Test
    void rejectsCodeDefaultPlaceholderSecret() {
        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(
                "presentation-coaching-local-jwt-secret-change-me-2026"
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder 기본값");
    }

    @Test
    void rejectsEnvExamplePlaceholderSecret() {
        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(
                "change-me-to-a-strong-random-secret-in-production"
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder 기본값");
    }

    @Test
    void acceptsRealSecretValue() {
        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(
                "a-sufficiently-random-real-secret-value-123"
        );

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }
}
