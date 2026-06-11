USE gpt_conversation_app;

ALTER TABLE users
  MODIFY COLUMN status ENUM('active','disabled','deleting') NOT NULL DEFAULT 'active';

ALTER TABLE analysis_jobs
  ADD COLUMN source_size_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER saved_filename;
