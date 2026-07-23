package com.hanium.presentation.application.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PasswordResetOutboxCrypto {

    public static final String LOCAL_DEFAULT_SECRET =
            "local-password-reset-outbox-key-change-me";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String VERSION_PREFIX = "v1:";

    private final SecretKeySpec encryptionKey;

    public PasswordResetOutboxCrypto(
            @Value("${password-reset.outbox.encryption-key:" + LOCAL_DEFAULT_SECRET + "}")
            String encryptionSecret
    ) {
        this.encryptionKey = new SecretKeySpec(sha256(encryptionSecret), "AES");
    }

    public String encrypt(String resetLink, String tokenHash) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(tokenHash.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(resetLink.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("비밀번호 재설정 링크를 암호화할 수 없습니다.", exception);
        }
    }

    public String decrypt(String encryptedResetLink, String tokenHash) {
        if (encryptedResetLink == null || !encryptedResetLink.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("지원하지 않는 비밀번호 재설정 outbox 암호문 형식입니다.");
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(
                    encryptedResetLink.substring(VERSION_PREFIX.length())
            );
            if (payload.length <= NONCE_LENGTH_BYTES) {
                throw new IllegalStateException("비밀번호 재설정 outbox 암호문이 손상되었습니다.");
            }

            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            byte[] ciphertext = new byte[payload.length - NONCE_LENGTH_BYTES];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(tokenHash.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "비밀번호 재설정 outbox 암호문을 복호화할 수 없습니다. 암호화 키 변경 여부를 확인하세요.",
                    exception
            );
        }
    }

    private byte[] sha256(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("password-reset.outbox.encryption-key는 비어 있을 수 없습니다.");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
