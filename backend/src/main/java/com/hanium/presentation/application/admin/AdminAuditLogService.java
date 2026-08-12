package com.hanium.presentation.application.admin;

import com.hanium.presentation.domain.admin.entity.AdminAuditLog;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import com.hanium.presentation.presentation.dto.response.AdminAuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AdminAuditLogService {

    private final AdminAuditLogRepository adminAuditLogRepository;

    public AdminAuditLogService(AdminAuditLogRepository adminAuditLogRepository) {
        this.adminAuditLogRepository = adminAuditLogRepository;
    }

    // 관리 액션을 실행하기 직전/직후 호출해 감사 기록을 남기는 용도입니다.
    // reason/requestId/incidentId는 파괴적 조치(정지, 강제 탈퇴, 결과 삭제, 수동
    // 재큐잉)에서만 값이 채워지고, 그 외 액션은 이전과 동일하게 null입니다(P2-03).
    @Transactional
    public void record(
            Long adminId,
            String adminEmail,
            AdminAuditAction action,
            AdminAuditTargetType targetType,
            String targetId,
            String detail,
            String reason,
            String requestId,
            String incidentId
    ) {
        adminAuditLogRepository.save(AdminAuditLog.create(
                adminId,
                adminEmail,
                action,
                targetType,
                targetId,
                detail,
                reason,
                requestId,
                incidentId
        ));
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLogResponse> getAuditLogs(
            String adminEmail,
            AdminAuditAction action,
            AdminAuditTargetType targetType,
            String targetId,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            Pageable pageable
    ) {
        return adminAuditLogRepository.search(
                        normalizeFilter(adminEmail),
                        action,
                        targetType,
                        normalizeFilter(targetId),
                        fromDateTime,
                        toDateTime,
                        pageable
                )
                .map(AdminAuditLogResponse::from);
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
