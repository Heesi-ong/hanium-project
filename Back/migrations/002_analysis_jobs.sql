USE gpt_conversation_app;

CREATE TABLE IF NOT EXISTS analysis_jobs (
  id CHAR(36) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  status ENUM('QUEUED','PROCESSING','COMPLETED','FAILED') NOT NULL DEFAULT 'QUEUED',
  original_filename VARCHAR(255) NOT NULL,
  saved_filename VARCHAR(255) NOT NULL,
  public_error VARCHAR(255) NULL,
  processing_time_seconds DECIMAL(12,2) NULL,
  total_score DECIMAL(6,2) NULL,
  summary_feedback TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_analysis_jobs_user_created (user_id, created_at),
  KEY idx_analysis_jobs_status_created (status, created_at),
  CONSTRAINT fk_analysis_jobs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
