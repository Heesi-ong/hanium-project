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
            String detail
    ) {
        this.adminId = adminId;
        this.adminEmail = adminEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }

    public static AdminAuditLog create(
            Long adminId,
            String adminEmail,
            AdminAuditAction action,
            AdminAuditTargetType targetType,
            String targetId,
            String detail
    ) {
        return new AdminAuditLog(adminId, adminEmail, action, targetType, targetId, detail);
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
