CREATE TABLE password_reset_email_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    password_reset_token_id BIGINT NOT NULL,
    recipient_email VARCHAR(254) NULL,
    encrypted_reset_link VARCHAR(2048) NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    processing_token VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT fk_password_reset_email_tasks_token
        FOREIGN KEY (password_reset_token_id) REFERENCES password_reset_tokens(id)
        ON DELETE CASCADE,
    CONSTRAINT uk_password_reset_email_tasks_token UNIQUE (password_reset_token_id),
    INDEX idx_password_reset_email_tasks_status_next (status, next_attempt_at),
    INDEX idx_password_reset_email_tasks_user_id (user_id)
);
