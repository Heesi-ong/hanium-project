ALTER TABLE analysis_jobs
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
