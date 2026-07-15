ALTER TABLE users
    ADD COLUMN purpose VARCHAR(30) NULL,
    ADD COLUMN experience_level VARCHAR(30) NULL,
    ADD COLUMN improvement_goal VARCHAR(30) NULL,
    ADD COLUMN onboarding_completed_at TIMESTAMP NULL;
