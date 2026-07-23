package com.hanium.presentation.domain.user.repository;

import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PasswordResetEmailTaskRepository
        extends JpaRepository<PasswordResetEmailTask, Long> {

    List<PasswordResetEmailTask> findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
            PasswordResetEmailTaskStatus status,
            LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from PasswordResetEmailTask task where task.id = :id")
    Optional<PasswordResetEmailTask> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("""
            update PasswordResetEmailTask task
            set task.status = :cancelled,
                task.completedAt = :now,
                task.lastError = :reason,
                task.recipientEmail = null,
                task.encryptedResetLink = null,
                task.processingToken = null
            where task.userId = :userId
              and task.status in :activeStatuses
            """)
    int cancelActiveTasksForUser(
            @Param("userId") Long userId,
            @Param("activeStatuses") List<PasswordResetEmailTaskStatus> activeStatuses,
            @Param("cancelled") PasswordResetEmailTaskStatus cancelled,
            @Param("now") LocalDateTime now,
            @Param("reason") String reason
    );

    long countByStatus(PasswordResetEmailTaskStatus status);

    Page<PasswordResetEmailTask> findByStatusOrderByCreatedAtDesc(
            PasswordResetEmailTaskStatus status,
            Pageable pageable
    );

    @Modifying
    @Query("""
            update PasswordResetEmailTask task
            set task.status = :cancelled,
                task.completedAt = :now,
                task.lastError = :reason,
                task.recipientEmail = null,
                task.encryptedResetLink = null,
                task.processingToken = null
            where task.status = :deadLetter
              and task.passwordResetToken.expiresAt < :now
            """)
    int cancelExpiredDeadLetters(
            @Param("deadLetter") PasswordResetEmailTaskStatus deadLetter,
            @Param("cancelled") PasswordResetEmailTaskStatus cancelled,
            @Param("now") LocalDateTime now,
            @Param("reason") String reason
    );
}
