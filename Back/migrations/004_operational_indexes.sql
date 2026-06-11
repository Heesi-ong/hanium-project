USE gpt_conversation_app;

ALTER TABLE analysis_jobs
  ADD INDEX idx_analysis_jobs_user_status_created (user_id, status, created_at);

ALTER TABLE conversations
  ADD INDEX idx_conversations_user_archived_updated (user_id, archived_at, updated_at);

ALTER TABLE gpt_usage
  ADD UNIQUE INDEX uq_gpt_usage_request_id (request_id);
