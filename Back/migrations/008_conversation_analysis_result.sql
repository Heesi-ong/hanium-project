USE gpt_conversation_app;

ALTER TABLE conversations
  ADD COLUMN analysis_result_id CHAR(36) NULL AFTER model_id,
  ADD INDEX idx_conversations_analysis_result (user_id, analysis_result_id);
