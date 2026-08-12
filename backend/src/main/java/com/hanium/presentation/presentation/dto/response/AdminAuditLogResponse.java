package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.admin.entity.AdminAuditLog;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;

import java.time.LocalDateTime;

public record AdminAuditLogResponse(
        Long id,
        String adminEmail,
        AdminAuditAction action,
        AdminAuditTargetType targetType,
        String targetId,
        String detail,
        String reason,
        String requestId,
        String incidentId,
        LocalDateTime createdAt
) {

    public static AdminAuditLogResponse from(AdminAuditLog auditLog) {
        return new AdminAuditLogResponse(
                auditLog.getId(),
                auditLog.getAdminEmail(),
                auditLog.getAction(),
                auditLog.getTargetType(),
                auditLog.getTargetId(),
                auditLog.getDetail(),
                auditLog.getReason(),
                auditLog.getRequestId(),
                auditLog.getIncidentId(),
                auditLog.getCreatedAt()
        );
    }
}
