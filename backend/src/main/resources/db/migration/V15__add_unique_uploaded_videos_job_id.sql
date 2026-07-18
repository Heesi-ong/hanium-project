-- job_id 중복 행이 남아있는 환경(예: 과거 업로드 재시도 경합으로 두 번 저장된 경우)에서는
-- UNIQUE 제약을 바로 걸면 마이그레이션 자체가 실패해 앱이 기동하지 못한다. 제약을 걸기
-- 전에 job_id별로 가장 최근 행(id가 가장 큰 행)만 남기고 오래된 중복을 먼저 정리한다.
-- 중복이 없는 환경(예: 로컬 개발 DB)에서는 삭제 대상이 없어 아무 영향도 주지 않는다.
DELETE older FROM uploaded_videos older
    INNER JOIN uploaded_videos newer
        ON older.job_id = newer.job_id
        AND older.id < newer.id;

ALTER TABLE uploaded_videos
    ADD CONSTRAINT uk_uploaded_videos_job_id UNIQUE (job_id);
