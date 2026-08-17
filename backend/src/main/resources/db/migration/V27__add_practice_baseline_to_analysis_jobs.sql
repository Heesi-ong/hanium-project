-- 완료된 자기 결과를 기준으로 재연습한 작업을 연결합니다.
-- 기준 결과가 삭제되어도 연습 결과 자체는 보존되도록 논리 jobId만 nullable로 저장합니다.
ALTER TABLE analysis_jobs
    ADD COLUMN baseline_job_id VARCHAR(50) NULL,
    ADD COLUMN practice_goal VARCHAR(30) NULL;

CREATE INDEX idx_analysis_jobs_baseline_created
    ON analysis_jobs (baseline_job_id, created_at);
