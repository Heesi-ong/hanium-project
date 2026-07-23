package com.hanium.presentation.domain.storage.repository;

import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.type.StorageDeletionTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;

public interface StorageDeletionTaskRepository extends JpaRepository<StorageDeletionTask, Long> {

    List<StorageDeletionTask> findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
            StorageDeletionTaskStatus status,
            LocalDateTime now
    );

    Page<StorageDeletionTask> findByStatusOrderByCreatedAtDesc(StorageDeletionTaskStatus status, Pageable pageable);

    long countByStatus(StorageDeletionTaskStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from StorageDeletionTask task where task.id = :id")
    java.util.Optional<StorageDeletionTask> findByIdForUpdate(@Param("id") Long id);

    boolean existsByActiveKey(String activeKey);

    List<StorageDeletionTask> findTop500ByStatusAndCompletedAtBeforeOrderByCompletedAtAsc(
            StorageDeletionTaskStatus status,
            LocalDateTime cutoff
    );
}
