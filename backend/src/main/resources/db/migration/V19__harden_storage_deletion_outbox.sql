ALTER TABLE storage_deletion_tasks
    ADD COLUMN active_key VARCHAR(320) NULL,
    ADD COLUMN processing_token VARCHAR(36) NULL;

-- V18을 먼저 사용한 환경에 미완료 행이 있을 수 있다. 기존 행은 id를 포함한 임시 key로
-- 보존해 migration 중 삭제 작업을 잃지 않게 하고, V19 이후 enqueue부터 canonical key를
-- 사용해 동일 prefix/reason의 활성 작업 중복 생성을 막는다.
UPDATE storage_deletion_tasks
SET active_key = CONCAT(reason, ':', object_key_prefix, ':legacy:', id)
WHERE status IN ('PENDING', 'DEAD_LETTER');

-- 같은 대상의 기존 활성 행이 여러 개라면 가장 오래된 한 건을 canonical key로 승격한다.
-- 나머지는 위 legacy key를 유지하므로 작업을 잃지 않으면서도 새 enqueue 중복은 차단된다.
UPDATE storage_deletion_tasks AS task
JOIN (
    SELECT grouped.canonical_id
    FROM (
        SELECT MIN(id) AS canonical_id
        FROM storage_deletion_tasks
        WHERE status IN ('PENDING', 'DEAD_LETTER')
        GROUP BY reason, object_key_prefix
    ) AS grouped
) AS canonical ON canonical.canonical_id = task.id
SET task.active_key = CONCAT(task.reason, ':', task.object_key_prefix);

CREATE UNIQUE INDEX uk_storage_deletion_tasks_active_key
    ON storage_deletion_tasks (active_key);

CREATE INDEX idx_storage_deletion_tasks_completed_at
    ON storage_deletion_tasks (status, completed_at);

CREATE TABLE storage_deletion_enqueue_locks (
    id INT PRIMARY KEY
);

INSERT INTO storage_deletion_enqueue_locks (id) VALUES (1);
