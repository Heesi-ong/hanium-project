package com.hanium.presentation.domain.storage.repository;

import com.hanium.presentation.domain.storage.entity.StorageDeletionEnqueueLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StorageDeletionEnqueueLockRepository
        extends JpaRepository<StorageDeletionEnqueueLock, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select enqueueLock from StorageDeletionEnqueueLock enqueueLock where enqueueLock.id = :id")
    Optional<StorageDeletionEnqueueLock> findByIdForUpdate(@Param("id") Integer id);
}
