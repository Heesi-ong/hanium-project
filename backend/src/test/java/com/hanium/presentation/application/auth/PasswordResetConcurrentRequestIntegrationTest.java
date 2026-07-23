package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "analysis.worker.enabled=false")
class PasswordResetConcurrentRequestIntegrationTest {

    private static final String EMAIL = "concurrent-reset@example.com";

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordResetEmailTaskRepository emailTaskRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        emailTaskRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.saveAndFlush(User.create(EMAIL, "hashed-password"));
    }

    @AfterEach
    void tearDown() {
        emailTaskRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void concurrentRequestsLeaveOnlyOneUsableTokenAndOnePendingEmail() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> requestAfterBarrier(ready, start));
            Future<?> second = executor.submit(() -> requestAfterBarrier(ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        LocalDateTime now = LocalDateTime.now();
        assertThat(tokenRepository.findAll())
                .filteredOn(token -> token.isUsable(now))
                .hasSize(1);
        assertThat(emailTaskRepository.findAll())
                .filteredOn(task -> task.getStatus() == PasswordResetEmailTaskStatus.PENDING)
                .hasSize(1);
        assertThat(emailTaskRepository.findAll())
                .filteredOn(task -> task.getStatus() == PasswordResetEmailTaskStatus.CANCELLED)
                .hasSize(1);
        assertThat(tokenRepository.findAll())
                .extracting(PasswordResetToken::getTokenHash)
                .doesNotHaveDuplicates();
    }

    private void requestAfterBarrier(CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            passwordResetService.requestPasswordReset(EMAIL);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
