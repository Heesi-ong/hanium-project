package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "password-reset.cleanup.retention-days=7"
})
class PasswordResetTokenCleanupServiceTest {

    @Autowired
    private PasswordResetTokenCleanupService passwordResetTokenCleanupService;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private SchedulerDistributedLock schedulerDistributedLock;

    private User user;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(User.create("cleanup-target@example.com", "encoded-password"));
        when(schedulerDistributedLock.tryLock(eq("password-reset-token-cleanup"), any(Duration.class)))
                .thenReturn(true);
    }

    // 이 테스트가 만든 사용자/토큰을 남겨두면, H2가 고정 이름(jdbc:h2:mem:presentation)의
    // 인메모리 DB를 테스트 스위트 전체에서 공유하는 구조상 이후 실행되는 다른 테스트
    // 클래스의 무조건적인 userRepository.deleteAll()이 이 토큰의 FK 참조 때문에 실패한다.
    @AfterEach
    void tearDown() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void deletesTokensExpiredBeyondRetentionWindowButKeepsRecentOnes() {
        PasswordResetToken longExpired = tokenWithExpiresAt(LocalDateTime.now().minusDays(8));
        PasswordResetToken recentlyExpired = tokenWithExpiresAt(LocalDateTime.now().minusDays(1));
        PasswordResetToken stillActive = tokenWithExpiresAt(LocalDateTime.now().plusMinutes(30));

        passwordResetTokenCleanupService.cleanupExpiredTokens();

        assertThat(passwordResetTokenRepository.findById(longExpired.getId())).isEmpty();
        assertThat(passwordResetTokenRepository.findById(recentlyExpired.getId())).isPresent();
        assertThat(passwordResetTokenRepository.findById(stillActive.getId())).isPresent();
    }

    @Test
    void deletesUsedTokensOnceTheirOriginalExpiryIsBeyondRetention() {
        PasswordResetToken usedLongAgo = tokenWithExpiresAt(LocalDateTime.now().minusDays(10));
        usedLongAgo.markUsed(LocalDateTime.now().minusDays(10));
        passwordResetTokenRepository.save(usedLongAgo);

        passwordResetTokenCleanupService.cleanupExpiredTokens();

        assertThat(passwordResetTokenRepository.findById(usedLongAgo.getId())).isEmpty();
    }

    @Test
    void skipsCleanupWhenDistributedLockIsAlreadyHeld() {
        PasswordResetToken longExpired = tokenWithExpiresAt(LocalDateTime.now().minusDays(30));
        when(schedulerDistributedLock.tryLock(eq("password-reset-token-cleanup"), any(Duration.class)))
                .thenReturn(false);

        passwordResetTokenCleanupService.cleanupExpiredTokens();

        assertThat(passwordResetTokenRepository.findById(longExpired.getId())).isPresent();
    }

    private PasswordResetToken tokenWithExpiresAt(LocalDateTime expiresAt) {
        return passwordResetTokenRepository.save(
                PasswordResetToken.create(user, "hash-" + expiresAt, expiresAt)
        );
    }
}
