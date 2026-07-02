-- 기존 분석 작업 데이터가 있는 환경에서도 마이그레이션이 실패하지 않도록 nullable로 추가합니다.
-- 신규 업로드 작업은 애플리케이션 코드에서 항상 로그인 사용자 id를 owner_id에 저장합니다.

ALTER TABLE analysis_jobs
    ADD COLUMN owner_id BIGINT;

ALTER TABLE analysis_jobs
    ADD CONSTRAINT fk_analysis_jobs_owner
        FOREIGN KEY (owner_id)
        REFERENCES users (id);

CREATE INDEX idx_analysis_jobs_owner_created_at
    ON analysis_jobs (owner_id, created_at);
