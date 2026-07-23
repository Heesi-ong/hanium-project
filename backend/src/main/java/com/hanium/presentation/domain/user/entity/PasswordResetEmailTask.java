package com.hanium.presentation.domain.user.entity;

import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "password_reset_email_tasks")
public class PasswordResetEmailTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "password_reset_token_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PasswordResetToken passwordResetToken;

    @Column(name = "recipient_email", length = 254)
    private String recipientEmail;

    @Column(name = "encrypted_reset_link", length = 2048)
    private String encryptedResetLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PasswordResetEmailTaskStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "processing_token", length = 36)
    private String processingToken;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected PasswordResetEmailTask() {
    }

    private PasswordResetEmailTask(
            Long userId,
            PasswordResetToken passwordResetToken,
            String recipientEmail,
            String encryptedResetLink
    ) {
        this.userId = userId;
        this.passwordResetToken = passwordResetToken;
        this.recipientEmail = recipientEmail;
        this.encryptedResetLink = encryptedResetLink;
        this.status = PasswordResetEmailTaskStatus.PENDING;
        this.nextAttemptAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }

    public static PasswordResetEmailTask create(
            Long userId,
            PasswordResetToken passwordResetToken,
            String recipientEmail,
            String encryptedResetLink
    ) {
        return new PasswordResetEmailTask(userId, passwordResetToken, recipientEmail, encryptedResetLink);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public PasswordResetToken getPasswordResetToken() {
        return passwordResetToken;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getEncryptedResetLink() {
        return encryptedResetLink;
    }

    public PasswordResetEmailTaskStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getProcessingToken() {
        return processingToken;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void claim(String token, LocalDateTime leaseExpiresAt) {
        this.processingToken = token;
        this.nextAttemptAt = leaseExpiresAt;
    }

    public boolean isClaimedBy(String token) {
        return token != null && token.equals(processingToken);
    }

    public void markCompleted() {
        this.status = PasswordResetEmailTaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        clearSensitivePayload();
    }

    public void cancel(String reason) {
        this.status = PasswordResetEmailTaskStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
        this.lastError = truncate(reason, 500);
        clearSensitivePayload();
    }

    public void markFailedAndScheduleRetry(
            String errorMessage,
            int maxAttempts,
            long baseBackoffSeconds,
            long maxBackoffSeconds
    ) {
        this.attemptCount++;
        this.lastError = truncate(errorMessage, 500);
        this.processingToken = null;

        if (attemptCount >= maxAttempts) {
            this.status = PasswordResetEmailTaskStatus.DEAD_LETTER;
            return;
        }

        long backoffSeconds = Math.min(
                maxBackoffSeconds,
                baseBackoffSeconds * (1L << Math.min(attemptCount - 1, 20))
        );
        this.nextAttemptAt = LocalDateTime.now().plus(Duration.ofSeconds(backoffSeconds));
    }

    public void requeue() {
        this.status = PasswordResetEmailTaskStatus.PENDING;
        this.attemptCount = 0;
        this.lastError = null;
        this.processingToken = null;
        this.nextAttemptAt = LocalDateTime.now();
        this.completedAt = null;
    }

    private void clearSensitivePayload() {
        this.recipientEmail = null;
        this.encryptedResetLink = null;
        this.processingToken = null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
