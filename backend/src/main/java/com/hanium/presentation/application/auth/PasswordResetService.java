package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
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
    private final PasswordResetEmailTaskService passwordResetEmailTaskService;
    private final PasswordEncoder passwordEncoder;
    private final long tokenExpirationMinutes;
    private final String resetUrlBase;
    private final boolean passwordResetEnabled;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordResetEmailTaskService passwordResetEmailTaskService,
            PasswordEncoder passwordEncoder,
            @Value("${password-reset.token-expiration-minutes:30}") long tokenExpirationMinutes,
            @Value("${password-reset.reset-url-base:http://localhost:5173/reset-password}") String resetUrlBase,
            @Value("${password-reset.enabled:true}") boolean passwordResetEnabled
    ) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetEmailTaskService = passwordResetEmailTaskService;
        this.passwordEncoder = passwordEncoder;
        this.tokenExpirationMinutes = tokenExpirationMinutes;
        this.resetUrlBase = resetUrlBase;
        this.passwordResetEnabled = passwordResetEnabled;
    }

    @Transactional
    public void requestPasswordReset(String email) {
        // 기능이 꺼져 있으면(운영자의 명시적 선택, 예: SMTP 미구성 환경) 계정 존재 여부와
        // 무관하게 항상 같은 비활성 오류를 반환합니다. 이메일 존재 여부를 확인하기 전에
        // 막으므로 계정 열거(enumeration) 방지 원칙은 그대로 유지됩니다.
        if (!passwordResetEnabled) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_DISABLED);
        }

        userRepository.findByEmailForUpdate(normalizeEmail(email)).ifPresent(user -> {
            // 동일 사용자 요청을 user row lock으로 직렬화한다. 먼저 이전 메일 작업을
            // 취소해 worker가 이미 무효화될 링크를 새로 발송하지 않게 한다.
            passwordResetEmailTaskService.cancelActiveTasksForUser(
                    user.getId(),
                    "SUPERSEDED_BY_NEW_PASSWORD_RESET_REQUEST"
            );

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
            passwordResetEmailTaskService.enqueue(user, resetToken, buildResetLink(token));
        });
    }

    @Transactional
    public boolean confirmPasswordReset(String token, String newPassword) {
        if (!passwordResetEnabled) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_DISABLED);
        }

        String tokenHash = hashToken(token);
        PasswordResetToken candidate = passwordResetTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (candidate == null) {
            return false;
        }

        // request 경로와 같은 user -> token 순서로 잠가 동시 새 요청/확인 간 deadlock과
        // 이중 확인을 막는다. 잠금 뒤 토큰을 다시 읽어 현재 상태를 재검증한다.
        User lockedUser = userRepository.findByIdForUpdate(candidate.getUser().getId()).orElse(null);
        if (lockedUser == null) {
            return false;
        }

        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashForUpdate(tokenHash)
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();

        if (resetToken == null || !resetToken.isUsable(now)) {
            return false;
        }

        lockedUser.changePasswordHash(passwordEncoder.encode(newPassword));
        resetToken.markUsed(now);

        // 이 토큰 외에 같은 사용자 앞으로 발급됐던 다른 활성 토큰(예: 이번 요청 이전에
        // requestPasswordReset이 여러 번 호출된 과거 데이터)도 비밀번호가 실제로 바뀐
        // 시점에 함께 무효화합니다. 방금 markUsed()로 처리한 토큰은 이미 usedAt이 설정돼
        // 있어 이 쿼리(usedAt IS NULL 대상)의 영향을 받지 않습니다.
        passwordResetTokenRepository.invalidateActiveTokensForUser(lockedUser, now);
        passwordResetEmailTaskService.cancelActiveTasksForUser(
                lockedUser.getId(),
                "PASSWORD_RESET_TOKEN_USED"
        );

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
