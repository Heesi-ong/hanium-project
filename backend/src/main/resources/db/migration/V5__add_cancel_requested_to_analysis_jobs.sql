ALTER TABLE analysis_jobs
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
