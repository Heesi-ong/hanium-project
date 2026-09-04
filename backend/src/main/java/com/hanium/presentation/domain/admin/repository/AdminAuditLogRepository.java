package com.hanium.presentation.domain.admin.repository;

import com.hanium.presentation.domain.admin.entity.AdminAuditLog;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    @Query("""
            select auditLog
            from AdminAuditLog auditLog
            where (:adminEmail is null or lower(auditLog.adminEmail) like lower(concat('%', :adminEmail, '%')))
              and (:action is null or auditLog.action = :action)
              and (:targetType is null or auditLog.targetType = :targetType)
              and (:targetId is null or auditLog.targetId like concat('%', :targetId, '%'))
              and (:fromDateTime is null or auditLog.createdAt >= :fromDateTime)
              and (:toDateTime is null or auditLog.createdAt <= :toDateTime)
            order by auditLog.createdAt desc
            """)
    Page<AdminAuditLog> search(
            @Param("adminEmail") String adminEmail,
            @Param("action") AdminAuditAction action,
            @Param("targetType") AdminAuditTargetType targetType,
            @Param("targetId") String targetId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );
}
