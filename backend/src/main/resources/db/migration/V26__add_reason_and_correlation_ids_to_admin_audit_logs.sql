-- 관리자 파괴적 조치(정지, 강제 탈퇴, 결과 삭제, 수동 재큐잉)의 사유와 상관관계를
-- 감사로그에 남기기 위한 컬럼입니다(2026-08-03 서비스화 점검 P2-03). 기존 행은 사유
-- 없이 기록됐으므로 NULL을 허용하고, 새 파괴적 조치 기록은 애플리케이션 레벨에서
-- reason을 필수로 검증합니다.
ALTER TABLE admin_audit_logs
    ADD COLUMN reason VARCHAR(500) NULL,
    ADD COLUMN request_id VARCHAR(100) NULL,
    ADD COLUMN incident_id VARCHAR(100) NULL;
