USE gpt_conversation_app;

ALTER TABLE analysis_jobs
  MODIFY COLUMN status ENUM('QUEUED','PROCESSING','COMPLETED','FAILED','CANCELLED')
    NOT NULL DEFAULT 'QUEUED',
  ADD COLUMN stage VARCHAR(50) NOT NULL DEFAULT 'queued' AFTER status,
  ADD COLUMN progress TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER stage,
  ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER progress,
  ADD COLUMN max_attempts INT UNSIGNED NOT NULL DEFAULT 3 AFTER attempt_count,
  ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE AFTER max_attempts,
  ADD COLUMN metrics JSON NULL AFTER summary_feedback,
  ADD COLUMN result_path VARCHAR(500) NULL AFTER metrics,
  ADD COLUMN source_expires_at DATETIME(3) NULL AFTER result_path,
  ADD COLUMN last_heartbeat_at DATETIME(3) NULL AFTER source_expires_at,
  ADD KEY idx_analysis_jobs_queue (status, cancel_requested, created_at),
  ADD KEY idx_analysis_jobs_source_expiry (source_expires_at);
