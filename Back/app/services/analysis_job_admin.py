"""관리자 화면과 readiness에서 사용하는 분석 작업 집계와 관리자 재시도 로직을 담당한다."""

from ..config import ANALYSIS_SOURCE_RETENTION_HOURS, WORKER_HEARTBEAT_STALE_SECONDS
from .admin_audit import insert_admin_audit
from .analysis_job_common import decode_job
from .database import get_connection, transaction


def get_analysis_queue_status():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                  SUM(status = 'QUEUED') AS queued,
                  SUM(status = 'PROCESSING') AS processing,
                  SUM(status = 'FAILED') AS failed,
                  SUM(status = 'PROCESSING' AND (
                    last_heartbeat_at IS NULL OR
                    last_heartbeat_at < DATE_SUB(NOW(3), INTERVAL %s SECOND)
                  )) AS stalled
                FROM analysis_jobs
                """,
                (WORKER_HEARTBEAT_STALE_SECONDS,),
            )
            result = cursor.fetchone() or {}
            return {key: int(result.get(key) or 0) for key in ("queued", "processing", "failed", "stalled")}
    finally:
        connection.close()


def get_admin_analysis_metrics():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                  COUNT(*) AS total,
                  SUM(status = 'COMPLETED') AS completed,
                  SUM(status = 'FAILED') AS failed,
                  SUM(status = 'CANCELLED') AS cancelled,
                  SUM(status = 'QUEUED') AS queued,
                  SUM(status = 'PROCESSING') AS processing,
                  AVG(CASE WHEN status = 'COMPLETED' THEN processing_time_seconds END)
                    AS average_completed_processing_seconds,
                  SUM(created_at >= DATE_SUB(NOW(3), INTERVAL 24 HOUR)) AS created_last_24_hours,
                  SUM(status = 'COMPLETED' AND completed_at >= DATE_SUB(NOW(3), INTERVAL 24 HOUR))
                    AS completed_last_24_hours,
                  SUM(status = 'FAILED' AND completed_at >= DATE_SUB(NOW(3), INTERVAL 24 HOUR))
                    AS failed_last_24_hours
                FROM analysis_jobs
                """
            )
            result = cursor.fetchone() or {}
    finally:
        connection.close()

    counts = {
        key: int(result.get(key) or 0)
        for key in (
            "total",
            "completed",
            "failed",
            "cancelled",
            "queued",
            "processing",
            "created_last_24_hours",
            "completed_last_24_hours",
            "failed_last_24_hours",
        )
    }
    terminal_count = counts["completed"] + counts["failed"]
    average_processing = result.get("average_completed_processing_seconds")
    return {
        **counts,
        "success_rate": round(counts["completed"] / terminal_count * 100, 2) if terminal_count else None,
        "failure_rate": round(counts["failed"] / terminal_count * 100, 2) if terminal_count else None,
        "average_completed_processing_seconds": (
            round(float(average_processing), 2) if average_processing is not None else None
        ),
    }


def list_admin_problem_jobs(limit=50):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT j.id AS result_id, j.status, j.stage, j.progress,
                       j.attempt_count, j.max_attempts, j.original_filename,
                       j.public_error, j.processing_time_seconds, j.total_score,
                       j.summary_feedback, j.metrics, j.created_at, j.started_at,
                       j.completed_at, j.last_heartbeat_at, u.email AS user_email,
                       (j.saved_filename <> '' AND j.status = 'FAILED'
                        AND j.attempt_count < j.max_attempts) AS retry_available
                FROM analysis_jobs j JOIN users u ON u.id = j.user_id
                WHERE j.status = 'FAILED' OR (
                  j.status = 'PROCESSING' AND (
                    j.last_heartbeat_at IS NULL OR
                    j.last_heartbeat_at < DATE_SUB(NOW(3), INTERVAL %s SECOND)
                  )
                )
                ORDER BY j.updated_at DESC
                LIMIT %s
                """,
                (WORKER_HEARTBEAT_STALE_SECONDS, limit),
            )
            return [decode_job(job) for job in cursor.fetchall()]
    finally:
        connection.close()


def retry_admin_job(job_id, admin_user):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'QUEUED', stage = 'queued', progress = 0,
                    cancel_requested = FALSE, public_error = NULL,
                    started_at = NULL, completed_at = NULL, last_heartbeat_at = NULL,
                    source_expires_at = DATE_ADD(NOW(3), INTERVAL %s HOUR)
                WHERE id = %s AND status = 'FAILED'
                  AND attempt_count < max_attempts AND saved_filename <> ''
                """,
                (ANALYSIS_SOURCE_RETENTION_HOURS, job_id),
            )
            if cursor.rowcount != 1:
                return False
            insert_admin_audit(
                cursor,
                actor_type="user",
                actor_user_id=admin_user["id"],
                actor_identifier=admin_user["email"],
                action="analysis_job.admin_retry",
                metadata={"job_id": job_id},
            )
            return True
