package com.hanium.presentation.domain.storage.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "storage_deletion_enqueue_locks")
public class StorageDeletionEnqueueLock {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    protected StorageDeletionEnqueueLock() {
    }

    private StorageDeletionEnqueueLock(Integer id) {
        this.id = id;
    }

    public static StorageDeletionEnqueueLock singleton() {
        return new StorageDeletionEnqueueLock(SINGLETON_ID);
    }
}
