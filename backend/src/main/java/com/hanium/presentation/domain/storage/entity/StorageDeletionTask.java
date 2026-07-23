package com.hanium.presentation.domain.storage.entity;

import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.storage.type.StorageDeletionTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * MinIO(오브젝트 스토리지) 프리픽스 삭제를 "요청 처리 중 best-effort로 시도하고 실패하면
 * 로그만 남기는" 방식 대신, DB에 영속된 작업(outbox)으로 관리한다. 업무 데이터 삭제와
 * 같은 트랜잭션에서 이 행을 생성해(원자성 보장) 요청이 끝난 뒤에도 별도 워커가 지수
 * backoff로 재시도하고, 한도를 넘으면 DEAD_LETTER로 표시해 관리자가 재처리할 수 있게 한다.
 * (2026-07-23 코드 리뷰 P1-03)
 */
@Entity
@Table(name = "storage_deletion_tasks")
public class StorageDeletionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, length = 50)
    private String jobId;

    @Column(name = "object_key_prefix", nullable = false, length = 255)
    private String objectKeyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StorageDeletionReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StorageDeletionTaskStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "active_key", length = 320, unique = true)
    private String activeKey;

    @Column(name = "processing_token", length = 36)
    private String processingToken;

    protected StorageDeletionTask() {
    }

    private StorageDeletionTask(String jobId, String objectKeyPrefix, StorageDeletionReason reason) {
        this.jobId = jobId;
        this.objectKeyPrefix = objectKeyPrefix;
        this.reason = reason;
        this.status = StorageDeletionTaskStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
        this.activeKey = buildActiveKey(objectKeyPrefix, reason);
    }

    public static StorageDeletionTask create(String jobId, String objectKeyPrefix, StorageDeletionReason reason) {
        return new StorageDeletionTask(jobId, objectKeyPrefix, reason);
    }

    public Long getId() {
        return id;
    }

    public String getJobId() {
        return jobId;
    }

    public String getObjectKeyPrefix() {
        return objectKeyPrefix;
    }

    public StorageDeletionReason getReason() {
        return reason;
    }

    public StorageDeletionTaskStatus getStatus() {
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getProcessingToken() {
        return processingToken;
    }

    public void claim(String token, LocalDateTime leaseExpiresAt) {
        this.processingToken = token;
        this.nextAttemptAt = leaseExpiresAt;
    }

    public boolean isClaimedBy(String token) {
        return token != null && token.equals(this.processingToken);
    }

    public void markCompleted() {
        this.status = StorageDeletionTaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.lastError = null;
        this.activeKey = null;
        this.processingToken = null;
    }

    // 실패할 때마다 시도 횟수를 늘리고, 2^attemptCount분(최대 maxBackoffMinutes)만큼
    // 뒤로 미룬 뒤 다시 시도한다. maxAttempts를 넘기면 더 이상 폴링 대상이 되지 않도록
    // DEAD_LETTER로 표시해, 관리자가 원인을 확인하고 수동으로 재큐잉하게 한다.
    public void markFailedAndScheduleRetry(
            String errorMessage,
            int maxAttempts,
            long baseBackoffMinutes,
            long maxBackoffMinutes
    ) {
        this.attemptCount++;
        this.lastError = truncate(errorMessage, 500);
        this.processingToken = null;

        if (this.attemptCount >= maxAttempts) {
            this.status = StorageDeletionTaskStatus.DEAD_LETTER;
            return;
        }

        long backoffMinutes = Math.min(
                maxBackoffMinutes,
                baseBackoffMinutes * (1L << Math.min(this.attemptCount, 20))
        );
        this.nextAttemptAt = LocalDateTime.now().plus(Duration.ofMinutes(backoffMinutes));
    }

    public void requeueForManualRetry() {
        this.status = StorageDeletionTaskStatus.PENDING;
        this.attemptCount = 0;
        this.lastError = null;
        this.nextAttemptAt = LocalDateTime.now();
        this.completedAt = null;
        this.processingToken = null;
    }

    public static String buildActiveKey(String objectKeyPrefix, StorageDeletionReason reason) {
        return reason.name() + ":" + objectKeyPrefix;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }
}
