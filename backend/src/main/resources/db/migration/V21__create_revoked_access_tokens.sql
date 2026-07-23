CREATE TABLE revoked_access_tokens (
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (token_hash),
    INDEX idx_revoked_access_tokens_expires_at (expires_at)
);
