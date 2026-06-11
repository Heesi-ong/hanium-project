USE gpt_conversation_app;

ALTER TABLE analysis_jobs
  ADD COLUMN idempotency_key VARCHAR(255) NULL AFTER user_id,
  ADD UNIQUE INDEX uq_analysis_jobs_user_idempotency (user_id, idempotency_key);
