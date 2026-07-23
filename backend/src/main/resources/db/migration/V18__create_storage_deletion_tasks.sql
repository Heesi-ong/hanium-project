CREATE TABLE storage_deletion_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(50) NOT NULL,
    object_key_prefix VARCHAR(255) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    INDEX idx_storage_deletion_tasks_status_next_attempt (status, next_attempt_at),
    INDEX idx_storage_deletion_tasks_job_id (job_id)
);
