package com.hanium.presentation.global.config;

import com.hanium.presentation.application.auth.PasswordResetOutboxCrypto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PasswordResetMailStartupValidatorTest {

    @Test
    void failsFastWhenEnabledButSmtpHostMissing() {
        PasswordResetMailStartupValidator validator =
                new PasswordResetMailStartupValidator("", true, "production-outbox-key-at-least-32-characters");

        // 기능이 켜져 있는데(기본값) SMTP가 없으면 운영자가 놓친 설정으로 간주해 기동을 막아야 합니다.
        assertThatIllegalStateException().isThrownBy(validator::validate);
    }

    @Test
    void doesNotThrowWhenSmtpHostConfigured() {
        PasswordResetMailStartupValidator validator =
                new PasswordResetMailStartupValidator(
                        "smtp.example.com",
                        true,
                        "production-outbox-key-at-least-32-characters"
                );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowWhenSmtpHostMissingButFeatureExplicitlyDisabled() {
        PasswordResetMailStartupValidator validator =
                new PasswordResetMailStartupValidator("", false, PasswordResetOutboxCrypto.LOCAL_DEFAULT_SECRET);

        // 운영자가 password-reset.enabled=false로 명시적으로 껐다면 SMTP가 없어도 기동을 막지 않습니다.
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsLocalDefaultOutboxEncryptionKeyInProd() {
        PasswordResetMailStartupValidator validator =
                new PasswordResetMailStartupValidator(
                        "smtp.example.com",
                        true,
                        PasswordResetOutboxCrypto.LOCAL_DEFAULT_SECRET
                );

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("PASSWORD_RESET_OUTBOX_ENCRYPTION_KEY");
    }
}
