package com.hanium.presentation.application.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PasswordResetOutboxCryptoTest {

    private static final String SECRET = "test-password-reset-outbox-secret-at-least-32";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String RESET_LINK = "https://example.com/reset-password?token=secret-token";

    @Test
    void encryptsAndDecryptsWithoutLeavingPlaintextInPayload() {
        PasswordResetOutboxCrypto crypto = new PasswordResetOutboxCrypto(SECRET);

        String encrypted = crypto.encrypt(RESET_LINK, TOKEN_HASH);

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("secret-token");
        assertThat(crypto.decrypt(encrypted, TOKEN_HASH)).isEqualTo(RESET_LINK);
    }

    @Test
    void rejectsDifferentTokenHashAsAad() {
        PasswordResetOutboxCrypto crypto = new PasswordResetOutboxCrypto(SECRET);
        String encrypted = crypto.encrypt(RESET_LINK, TOKEN_HASH);

        assertThatIllegalStateException()
                .isThrownBy(() -> crypto.decrypt(encrypted, "b".repeat(64)));
    }

    @Test
    void rejectsCiphertextEncryptedWithDifferentKey() {
        PasswordResetOutboxCrypto first =
                new PasswordResetOutboxCrypto("first-password-reset-outbox-secret-32chars");
        PasswordResetOutboxCrypto second =
                new PasswordResetOutboxCrypto("second-password-reset-outbox-secret-32chars");

        String encrypted = first.encrypt(RESET_LINK, TOKEN_HASH);

        assertThatIllegalStateException()
                .isThrownBy(() -> second.decrypt(encrypted, TOKEN_HASH));
    }
}
