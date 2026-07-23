package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.storage.type.StorageDeletionTaskStatus;

import java.time.LocalDateTime;

public record AdminStorageDeletionTaskResponse(
        Long id,
        String jobId,
        String objectKeyPrefix,
        StorageDeletionReason reason,
        StorageDeletionTaskStatus status,
        int attemptCount,
        String lastError,
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {

    public static AdminStorageDeletionTaskResponse from(StorageDeletionTask task) {
        return new AdminStorageDeletionTaskResponse(
                task.getId(),
                task.getJobId(),
                task.getObjectKeyPrefix(),
                task.getReason(),
                task.getStatus(),
                task.getAttemptCount(),
                task.getLastError(),
                task.getNextAttemptAt(),
                task.getCreatedAt(),
                task.getCompletedAt()
        );
    }
}
