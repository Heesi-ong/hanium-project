package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.storage.StorageDeletionTaskService;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminStorageDeletionTaskActionService {

    private final StorageDeletionTaskService storageDeletionTaskService;
    private final AdminAuditLogService adminAuditLogService;

    public AdminStorageDeletionTaskActionService(
            StorageDeletionTaskService storageDeletionTaskService,
            AdminAuditLogService adminAuditLogService
    ) {
        this.storageDeletionTaskService = storageDeletionTaskService;
        this.adminAuditLogService = adminAuditLogService;
    }

    @Transactional
    public void requeueDeadLetterTask(Long adminId, String adminEmail, Long taskId) {
        storageDeletionTaskService.requeueForManualRetry(taskId);

        adminAuditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.REQUEUE_STORAGE_DELETION_TASK,
                AdminAuditTargetType.STORAGE_DELETION_TASK,
                String.valueOf(taskId),
                null
        );
    }
}
