-- 동일한 Video LLM 재분석 접수가 네트워크 재시도나 다중 backend 인스턴스 경합으로
-- 중복 생성·중복 과금되지 않도록 DB 최종 방어선을 추가합니다.

ALTER TABLE analysis_jobs
    ADD COLUMN reanalysis_idempotency_key_hash CHAR(64) NULL,
    ADD COLUMN active_reanalysis_source_job_id VARCHAR(50)
        GENERATED ALWAYS AS (
            CASE
                WHEN analysis_kind = 'VIDEO_LLM_REANALYSIS'
                    AND status IN (
                        'UPLOADED',
                        'QUEUED',
                        'BASIC_ANALYZING',
                        'VIDEO_LLM_ANALYZING',
                        'COMPACTING',
                        'OPENAI_GENERATING',
                        'MERGING_RESULT'
                    )
                THEN source_job_id
                ELSE NULL
            END
        ) STORED;

CREATE UNIQUE INDEX uk_analysis_jobs_reanalysis_idempotency
    ON analysis_jobs (owner_id, source_job_id, reanalysis_idempotency_key_hash);

CREATE UNIQUE INDEX uk_analysis_jobs_active_reanalysis_source
    ON analysis_jobs (active_reanalysis_source_job_id);
