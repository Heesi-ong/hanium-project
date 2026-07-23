package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.auth.PasswordResetEmailTaskService;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPasswordResetEmailTaskActionService {

    private final PasswordResetEmailTaskService taskService;
    private final AdminAuditLogService auditLogService;

    public AdminPasswordResetEmailTaskActionService(
            PasswordResetEmailTaskService taskService,
            AdminAuditLogService auditLogService
    ) {
        this.taskService = taskService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void requeueDeadLetter(Long adminId, String adminEmail, Long taskId) {
        taskService.requeueDeadLetter(taskId);
        auditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.REQUEUE_PASSWORD_RESET_EMAIL_TASK,
                AdminAuditTargetType.PASSWORD_RESET_EMAIL_TASK,
                String.valueOf(taskId),
                null
        );
    }
}
