-- 분석 실행 옵션(useVideoLlm / useOpenAi)을 job에 영속화합니다.
-- 지금까지 이 옵션은 /run 요청 파라미터로만 전달되고 저장되지 않았습니다. 그래서 서버가
-- 재시작되면 "대기 중이던 작업을 어떤 옵션으로 실행해야 하는지"를 알 수 없어 이어서 실행할
-- 수 없었습니다. 재시작 복구(대기 작업 재투입)를 위해 옵션을 DB에 남깁니다.
-- 기존 데이터가 있어도 실패하지 않도록 기본값 TRUE로 추가합니다.
ALTER TABLE analysis_jobs
    ADD COLUMN use_video_llm BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE analysis_jobs
    ADD COLUMN use_openai BOOLEAN NOT NULL DEFAULT TRUE;

-- 멈춘 작업 워치도그와 대기 작업 재투입 스케줄러가 status + started_at 기준으로
-- 후보를 조회하므로, 해당 조회를 빠르게 하기 위한 인덱스를 추가합니다.
CREATE INDEX idx_analysis_jobs_status_started_at
    ON analysis_jobs (status, started_at);
