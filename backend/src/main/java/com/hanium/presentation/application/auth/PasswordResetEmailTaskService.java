package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PasswordResetEmailTaskService {

    private static final List<PasswordResetEmailTaskStatus> ACTIVE_STATUSES = List.of(
            PasswordResetEmailTaskStatus.PENDING,
            PasswordResetEmailTaskStatus.DEAD_LETTER
    );

    private final PasswordResetEmailTaskRepository taskRepository;
    private final PasswordResetOutboxCrypto outboxCrypto;

    public PasswordResetEmailTaskService(
            PasswordResetEmailTaskRepository taskRepository,
            PasswordResetOutboxCrypto outboxCrypto
    ) {
        this.taskRepository = taskRepository;
        this.outboxCrypto = outboxCrypto;
    }

    @Transactional
    public void enqueue(User user, PasswordResetToken resetToken, String resetLink) {
        String encryptedResetLink = outboxCrypto.encrypt(resetLink, resetToken.getTokenHash());
        taskRepository.save(PasswordResetEmailTask.create(
                user.getId(),
                resetToken,
                user.getEmail(),
                encryptedResetLink
        ));
    }

    @Transactional
    public int cancelActiveTasksForUser(Long userId, String reason) {
        return taskRepository.cancelActiveTasksForUser(
                userId,
                ACTIVE_STATUSES,
                PasswordResetEmailTaskStatus.CANCELLED,
                LocalDateTime.now(),
                reason
        );
    }

    @Transactional
    public void requeueDeadLetter(Long taskId) {
        PasswordResetEmailTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_RESET_EMAIL_TASK_NOT_FOUND));

        if (task.getStatus() != PasswordResetEmailTaskStatus.DEAD_LETTER
                || task.getEncryptedResetLink() == null
                || !task.getPasswordResetToken().isUsable(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_EMAIL_REQUEUE_NOT_ALLOWED);
        }

        task.requeue();
    }
}
