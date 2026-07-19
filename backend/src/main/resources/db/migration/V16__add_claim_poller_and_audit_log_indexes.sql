-- QueuedAnalysisJobPoller가 기본 1초 간격으로 반복 실행하는 이 앱의 가장 빈번한 쿼리
-- (findByStatusOrderByCreatedAtAscForClaim: WHERE status = :status ORDER BY created_at ASC)를
-- 위한 인덱스입니다. 기존 idx_analysis_jobs_status_started_at(V6)은 started_at 기준이라
-- 이 쿼리의 정렬에는 쓰이지 않아, QUEUED 후보가 늘어나면 매초 filesort가 발생합니다.
CREATE INDEX idx_analysis_jobs_status_created_at
    ON analysis_jobs (status, created_at);

-- admin_audit_logs는 관리자 조치가 있을 때마다 계속 늘어나기만 하는 테이블이라(삭제 없음),
-- 유일한 조회 경로인 findAllByOrderByCreatedAtDesc가 시간이 지날수록 점점 느려집니다.
CREATE INDEX idx_admin_audit_logs_created_at
    ON admin_audit_logs (created_at);
