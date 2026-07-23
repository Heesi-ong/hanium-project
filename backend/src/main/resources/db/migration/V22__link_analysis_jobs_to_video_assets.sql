-- 분석 실행(analysis_jobs)과 불변 원본 영상 asset(uploaded_videos)의 생명주기를
-- 분리하기 위한 첫 migration입니다. 이 단계에서는 기존 job_id 조회 계약을 유지하면서
-- 명시적인 FK를 추가·backfill합니다.

ALTER TABLE analysis_jobs
    ADD COLUMN video_asset_id BIGINT NULL;

UPDATE analysis_jobs jobs
    INNER JOIN uploaded_videos videos
        ON videos.job_id = jobs.job_id
SET jobs.video_asset_id = videos.id
WHERE jobs.video_asset_id IS NULL;

CREATE INDEX idx_analysis_jobs_video_asset_id
    ON analysis_jobs (video_asset_id);

ALTER TABLE analysis_jobs
    ADD CONSTRAINT fk_analysis_jobs_video_asset
        FOREIGN KEY (video_asset_id)
        REFERENCES uploaded_videos (id)
        ON DELETE SET NULL;
