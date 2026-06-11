USE gpt_conversation_app;

ALTER TABLE analysis_jobs
  ADD COLUMN result_size_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER source_size_bytes;
