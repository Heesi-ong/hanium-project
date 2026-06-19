USE gpt_conversation_app;

CREATE TABLE IF NOT EXISTS admin_audit_logs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_type VARCHAR(50) NOT NULL,
  actor_user_id BIGINT UNSIGNED NULL,
  actor_identifier VARCHAR(255) NOT NULL,
  action VARCHAR(100) NOT NULL,
  target_user_id BIGINT UNSIGNED NULL,
  target_email VARCHAR(255) NULL,
  metadata JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_admin_audit_logs_created (created_at),
  KEY idx_admin_audit_logs_action_created (action, created_at),
  KEY idx_admin_audit_logs_actor_created (actor_user_id, created_at),
  KEY idx_admin_audit_logs_target_created (target_user_id, created_at),
  CONSTRAINT fk_admin_audit_logs_actor_user
    FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL,
  CONSTRAINT fk_admin_audit_logs_target_user
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
