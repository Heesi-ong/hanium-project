package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetEmailSender passwordResetEmailSender;
    private final PasswordEncoder passwordEncoder;
    private final long tokenExpirationMinutes;
    private final String resetUrlBase;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetEmailSender passwordResetEmailSender,
            PasswordEncoder passwordEncoder,
            @Value("${password-reset.token-expiration-minutes:30}") long tokenExpirationMinutes,
            @Value("${password-reset.reset-url-base:http://localhost:5173/reset-password}") String resetUrlBase
    ) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetEmailSender = passwordResetEmailSender;
        this.passwordEncoder = passwordEncoder;
        this.tokenExpirationMinutes = tokenExpirationMinutes;
        this.resetUrlBase = resetUrlBase;
    }

    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(normalizeEmail(email)).ifPresent(user -> {
            // 사용자당 활성 재설정 토큰은 항상 1개만 유효해야 합니다. 새 토큰을 만들기 전에
            // 기존에 발급했던(아직 사용되지 않은) 토큰을 모두 무효화해, 이전 메일의 링크가
            // 새 링크 발급 이후에도 계속 살아있는 상태를 없앱니다.
            passwordResetTokenRepository.invalidateActiveTokensForUser(user, LocalDateTime.now());

            String token = generateToken();
            PasswordResetToken resetToken = PasswordResetToken.create(
                    user,
                    hashToken(token),
                    LocalDateTime.now().plusMinutes(tokenExpirationMinutes)
            );
            passwordResetTokenRepository.save(resetToken);
            passwordResetEmailSender.sendPasswordResetLink(user, buildResetLink(token));
        });
    }

    @Transactional
    public boolean confirmPasswordReset(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(hashToken(token))
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();

        if (resetToken == null || !resetToken.isUsable(now)) {
            return false;
        }

        User user = resetToken.getUser();
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        resetToken.markUsed(now);

        // 이 토큰 외에 같은 사용자 앞으로 발급됐던 다른 활성 토큰(예: 이번 요청 이전에
        // requestPasswordReset이 여러 번 호출된 과거 데이터)도 비밀번호가 실제로 바뀐
        // 시점에 함께 무효화합니다. 방금 markUsed()로 처리한 토큰은 이미 usedAt이 설정돼
        // 있어 이 쿼리(usedAt IS NULL 대상)의 영향을 받지 않습니다.
        passwordResetTokenRepository.invalidateActiveTokensForUser(user, now);

        return true;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildResetLink(String token) {
        String separator = resetUrlBase.contains("?") ? "&" : "?";
        return resetUrlBase + separator + "token=" + token;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
