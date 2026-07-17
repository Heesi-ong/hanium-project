package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class PasswordResetMailStartupValidatorTest {

    @Test
    void warnsButDoesNotThrowWhenSmtpHostMissing() {
        PasswordResetMailStartupValidator validator =
                new PasswordResetMailStartupValidator("");

        // 이메일 미설정은 경고만 남기고 기동을 막지 않아야 합니다(fail-fast 아님).
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowWhenSmtpHostConfigured() {
        PasswordResetMailStartupValidator validator =
                new PasswordResetMailStartupValidator("smtp.example.com");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }
}
