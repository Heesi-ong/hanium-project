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

@Service
public class AdminAuditLogService {

    private final AdminAuditLogRepository adminAuditLogRepository;

    public AdminAuditLogService(AdminAuditLogRepository adminAuditLogRepository) {
        this.adminAuditLogRepository = adminAuditLogRepository;
    }

    // 5-5f/g/h(정지, 강제 탈퇴, 결과 삭제)에서 관리 액션을 실행하기 직전/직후 호출해
    // 감사 기록을 남기는 용도입니다. 이 Unit 자체에는 호출하는 액션이 아직 없습니다.
    @Transactional
    public void record(
            Long adminId,
            String adminEmail,
            AdminAuditAction action,
            AdminAuditTargetType targetType,
            String targetId,
            String detail
    ) {
        adminAuditLogRepository.save(AdminAuditLog.create(
                adminId,
                adminEmail,
                action,
                targetType,
                targetId,
                detail
        ));
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLogResponse> getAuditLogs(Pageable pageable) {
        return adminAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(AdminAuditLogResponse::from);
    }
}
