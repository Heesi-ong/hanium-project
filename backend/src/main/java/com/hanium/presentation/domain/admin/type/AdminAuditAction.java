package com.hanium.presentation.domain.admin.type;

public enum AdminAuditAction {
    SUSPEND_USER,
    ACTIVATE_USER,
    FORCE_WITHDRAW_USER,
    DELETE_RESULT,
    REQUEUE_DEAD_LETTER_JOB,
    REQUEUE_STORAGE_DELETION_TASK,
    REQUEUE_PASSWORD_RESET_EMAIL_TASK
}
