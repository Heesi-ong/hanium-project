ALTER TABLE users
    ADD COLUMN terms_agreed_at TIMESTAMP NULL,
    ADD COLUMN terms_version VARCHAR(20) NULL;
