-- 재분석 결과를 원본과 별도 job으로 보존하기 위한 lineage와, JSON 파일에만 있던
-- Video LLM 생성 방식을 DB 상태에 함께 기록합니다.

ALTER TABLE analysis_jobs
    ADD COLUMN analysis_kind VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN source_job_id VARCHAR(50) NULL,
    ADD COLUMN video_llm_generation_mode VARCHAR(20) NULL;

CREATE INDEX idx_analysis_jobs_source_kind_created
    ON analysis_jobs (source_job_id, analysis_kind, created_at);

CREATE INDEX idx_analysis_jobs_video_llm_mode
    ON analysis_jobs (video_llm_generation_mode);
