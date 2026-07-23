package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;

import java.time.LocalDateTime;

public record AdminPasswordResetEmailTaskResponse(
        Long id,
        Long userId,
        String maskedRecipientEmail,
        PasswordResetEmailTaskStatus status,
        int attemptCount,
        String lastError,
        LocalDateTime nextAttemptAt,
        LocalDateTime tokenExpiresAt,
        LocalDateTime createdAt
) {

    public static AdminPasswordResetEmailTaskResponse from(PasswordResetEmailTask task) {
        return new AdminPasswordResetEmailTaskResponse(
                task.getId(),
                task.getUserId(),
                maskEmail(task.getRecipientEmail()),
                task.getStatus(),
                task.getAttemptCount(),
                task.getLastError(),
                task.getNextAttemptAt(),
                task.getPasswordResetToken().getExpiresAt(),
                task.getCreatedAt()
        );
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String maskedLocal = local.length() <= 2
                ? "*".repeat(local.length())
                : local.substring(0, 2) + "*".repeat(local.length() - 2);
        return maskedLocal + email.substring(at);
    }
}
