package com.hanium.presentation.domain.admin.entity;

import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "admin_email", nullable = false, length = 255)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private AdminAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private AdminAuditTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 50)
    private String targetId;

    @Column(name = "detail", length = 500)
    private String detail;

    // 파괴적 조치(정지, 강제 탈퇴, 결과 삭제, 수동 재큐잉)는 관리자가 입력한 사유를
    // 필수로 남긴다. 그 외 액션(예: 계정 활성화)은 이전과 동일하게 null이다(P2-03).
    @Column(name = "reason", length = 500)
    private String reason;

    // 같은 요청을 여러 시스템에서 추적할 수 있도록 컨트롤러가 발급하는 상관 ID입니다.
    // 관리자가 입력하지 않으므로 항상 값이 있습니다(파괴적 조치에 한함).
    @Column(name = "request_id", length = 100)
    private String requestId;

    // 관리자가 외부 인시던트/문의 티켓 번호를 선택적으로 남길 수 있는 필드입니다.
    @Column(name = "incident_id", length = 100)
    private String incidentId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AdminAuditLog() {
    }

    private AdminAuditLog(
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
        this.adminId = adminId;
        this.adminEmail = adminEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.reason = reason;
        this.requestId = requestId;
        this.incidentId = incidentId;
        this.createdAt = LocalDateTime.now();
    }

    public static AdminAuditLog create(
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
        return new AdminAuditLog(
                adminId,
                adminEmail,
                action,
                targetType,
                targetId,
                detail,
                reason,
                requestId,
                incidentId
        );
    }

    public Long getId() {
        return id;
    }

    public Long getAdminId() {
        return adminId;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public AdminAuditAction getAction() {
        return action;
    }

    public AdminAuditTargetType getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getDetail() {
        return detail;
    }

    public String getReason() {
        return reason;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
