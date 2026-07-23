package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "password-reset.outbox.max-attempts=3",
        "password-reset.outbox.base-backoff-seconds=1",
        "password-reset.outbox.max-backoff-seconds=10",
        "password-reset.outbox.claim-lease-seconds=10",
        "scheduler.lock.password-reset-email-worker-ttl-seconds=4"
})
class PasswordResetEmailOutboxWorkerTest {

    private static final String RESET_LINK =
            "https://example.com/reset-password?token=worker-secret-token";

    @Autowired
    private PasswordResetEmailOutboxWorker worker;

    @Autowired
    private PasswordResetEmailTaskService taskService;

    @Autowired
    private PasswordResetEmailTaskRepository taskRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private PasswordResetEmailSender emailSender;

    @MockBean
    private SchedulerDistributedLock distributedLock;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        when(distributedLock.tryLock(eq("password-reset-email-worker"), any(Duration.class)))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        taskRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void sendsOutsideTransactionAndClearsSensitivePayload() {
        PasswordResetEmailTask task = createTask("outbox-success@example.com", 30);
        makeDue(task);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.getArgument(2, String.class)).isEqualTo(RESET_LINK);
            return null;
        }).when(emailSender).sendPasswordResetLink(
                eq("outbox-success@example.com"),
                anyLong(),
                anyString()
        );

        worker.processPendingEmails();

        PasswordResetEmailTask reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.COMPLETED);
        assertThat(reloaded.getRecipientEmail()).isNull();
        assertThat(reloaded.getEncryptedResetLink()).isNull();
        assertThat(reloaded.getProcessingToken()).isNull();
    }

    @Test
    void schedulesRetryWhenSmtpFails() {
        PasswordResetEmailTask task = createTask("outbox-retry@example.com", 30);
        makeDue(task);
        doThrow(new RuntimeException("smtp unavailable"))
                .when(emailSender)
                .sendPasswordResetLink(eq("outbox-retry@example.com"), anyLong(), anyString());

        worker.processPendingEmails();

        PasswordResetEmailTask reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("smtp unavailable");
        assertThat(reloaded.getNextAttemptAt()).isAfter(LocalDateTime.now());
        assertThat(reloaded.getEncryptedResetLink()).isNotBlank();
    }

    @Test
    void movesToDeadLetterAfterRetryBudgetIsExhausted() {
        PasswordResetEmailTask task = createTask("outbox-dead@example.com", 30);
        ReflectionTestUtils.setField(task, "attemptCount", 2);
        makeDue(task);
        doThrow(new RuntimeException("smtp unavailable"))
                .when(emailSender)
                .sendPasswordResetLink(eq("outbox-dead@example.com"), anyLong(), anyString());

        worker.processPendingEmails();

        PasswordResetEmailTask reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.DEAD_LETTER);
        assertThat(reloaded.getAttemptCount()).isEqualTo(3);
        assertThat(reloaded.getEncryptedResetLink()).isNotBlank();
    }

    @Test
    void cancelsTaskWithoutSendingWhenTokenExpired() {
        PasswordResetEmailTask task = createTask("outbox-expired@example.com", -1);
        makeDue(task);

        worker.processPendingEmails();

        PasswordResetEmailTask reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.CANCELLED);
        assertThat(reloaded.getRecipientEmail()).isNull();
        assertThat(reloaded.getEncryptedResetLink()).isNull();
        verify(emailSender, never()).sendPasswordResetLink(anyString(), anyLong(), anyString());
    }

    @Test
    void retriesAfterAbandonedClaimLeaseExpires() {
        PasswordResetEmailTask task = createTask("outbox-lease@example.com", 30);
        task.claim("abandoned-token", LocalDateTime.now().minusSeconds(1));
        taskRepository.saveAndFlush(task);

        worker.processPendingEmails();

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(PasswordResetEmailTaskStatus.COMPLETED);
    }

    @Test
    void skipsWhenDistributedLockIsHeldElsewhere() {
        PasswordResetEmailTask task = createTask("outbox-locked@example.com", 30);
        makeDue(task);
        when(distributedLock.tryLock(eq("password-reset-email-worker"), any(Duration.class)))
                .thenReturn(false);

        worker.processPendingEmails();

        verify(emailSender, never()).sendPasswordResetLink(anyString(), anyLong(), anyString());
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(PasswordResetEmailTaskStatus.PENDING);
    }

    @Test
    void cancelsExpiredDeadLetterAndClearsSensitivePayload() {
        PasswordResetEmailTask task = createTask("outbox-expired-dead@example.com", -1);
        task.markFailedAndScheduleRetry("smtp unavailable", 1, 1, 10);
        taskRepository.saveAndFlush(task);

        worker.processPendingEmails();

        PasswordResetEmailTask reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.CANCELLED);
        assertThat(reloaded.getRecipientEmail()).isNull();
        assertThat(reloaded.getEncryptedResetLink()).isNull();
        verify(emailSender, never()).sendPasswordResetLink(anyString(), anyLong(), anyString());
    }

    @Test
    void deletingUserCascadesTokenAndEmailTask() {
        PasswordResetEmailTask task = createTask("outbox-withdrawal@example.com", 30);

        userRepository.deleteById(task.getUserId());
        userRepository.flush();

        assertThat(tokenRepository.findAll()).isEmpty();
        assertThat(taskRepository.findAll()).isEmpty();
    }

    private PasswordResetEmailTask createTask(String email, long expiresInMinutes) {
        User user = userRepository.saveAndFlush(User.create(email, "hashed-password"));
        PasswordResetToken token = tokenRepository.saveAndFlush(PasswordResetToken.create(
                user,
                "a".repeat(64),
                LocalDateTime.now().plusMinutes(expiresInMinutes)
        ));
        taskService.enqueue(user, token, RESET_LINK);
        return taskRepository.findAll().get(0);
    }

    private void makeDue(PasswordResetEmailTask task) {
        ReflectionTestUtils.setField(task, "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        taskRepository.saveAndFlush(task);
    }
}
