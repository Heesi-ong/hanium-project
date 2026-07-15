CREATE TABLE ai_coach_conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(50) NOT NULL,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_coach_conversations_job_id UNIQUE (job_id),
    INDEX idx_ai_coach_conversations_owner_id (owner_id)
);

CREATE TABLE ai_coach_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    generation_mode VARCHAR(20) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_coach_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_coach_conversations(id)
        ON DELETE CASCADE,
    INDEX idx_ai_coach_messages_conversation_id (conversation_id)
);
