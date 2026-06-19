"""분석 작업의 생성, 상태 전이, 취소, 재시도, 복구 로직을 담당한다."""

import json

from ..config import ANALYSIS_SOURCE_RETENTION_HOURS, USER_MAX_ACTIVE_ANALYSES, USER_STORAGE_QUOTA_MB
from .analysis_job_common import PUBLIC_JOB_COLUMNS, decode_job
from .database import get_connection, transaction


def reserve_analysis_job(job_id, user_id, original_filename, saved_filename, source_size_bytes, idempotency_key=None):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT status FROM users WHERE id = %s FOR UPDATE", (user_id,))
            user = cursor.fetchone()
            if not user or user["status"] != "active":
                return {"outcome": "inactive"}
            if idempotency_key:
                cursor.execute(
                    f"SELECT {PUBLIC_JOB_COLUMNS} FROM analysis_jobs "
                    "WHERE user_id = %s AND idempotency_key = %s LIMIT 1",
                    (user_id, idempotency_key),
                )
                existing = decode_job(cursor.fetchone())
                if existing:
                    return {"outcome": "existing", "job": existing}
            cursor.execute(
                "SELECT COUNT(*) AS count FROM analysis_jobs "
                "WHERE user_id = %s AND status IN ('QUEUED','PROCESSING')",
                (user_id,),
            )
            if int(cursor.fetchone()["count"]) >= USER_MAX_ACTIVE_ANALYSES:
                return {"outcome": "active_limit"}
            cursor.execute(
                "SELECT COALESCE(SUM(source_size_bytes + result_size_bytes), 0) AS reserved_bytes "
                "FROM analysis_jobs WHERE user_id = %s",
                (user_id,),
            )
            reserved_bytes = int(cursor.fetchone()["reserved_bytes"])
            if reserved_bytes + source_size_bytes > USER_STORAGE_QUOTA_MB * 1024 * 1024:
                return {"outcome": "quota_limit"}
            cursor.execute(
                """
                INSERT INTO analysis_jobs
                  (id, user_id, idempotency_key, status, stage, progress, original_filename,
                   saved_filename, source_size_bytes, source_expires_at)
                VALUES (%s, %s, %s, 'QUEUED', 'queued', 0, %s, %s, %s,
                        DATE_ADD(NOW(3), INTERVAL %s HOUR))
                """,
                (
                    job_id,
                    user_id,
                    idempotency_key,
                    original_filename,
                    saved_filename,
                    source_size_bytes,
                    ANALYSIS_SOURCE_RETENTION_HOURS,
                ),
            )
            return {"outcome": "created"}


def create_analysis_job(job_id, user_id, original_filename, saved_filename, idempotency_key=None, source_size_bytes=0):
    return reserve_analysis_job(
        job_id, user_id, original_filename, saved_filename, source_size_bytes, idempotency_key=idempotency_key
    )


def claim_next_job():
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, user_id, original_filename, saved_filename, attempt_count, max_attempts
                FROM analysis_jobs
                WHERE status = 'QUEUED' AND cancel_requested = FALSE
                ORDER BY created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """
            )
            job = cursor.fetchone()
            if not job:
                return None
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'PROCESSING', stage = 'preparing', progress = 5,
                    attempt_count = attempt_count + 1, public_error = NULL,
                    started_at = NOW(3), completed_at = NULL, last_heartbeat_at = NOW(3)
                WHERE id = %s AND status = 'QUEUED'
                """,
                (job["id"],),
            )
            return job if cursor.rowcount == 1 else None


def update_job_progress(job_id, stage, progress):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET stage = %s, progress = %s, last_heartbeat_at = NOW(3)
                WHERE id = %s AND status = 'PROCESSING' AND cancel_requested = FALSE
                """,
                (stage, max(0, min(99, int(progress))), job_id),
            )
            return cursor.rowcount == 1


def is_cancel_requested(job_id):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT cancel_requested FROM analysis_jobs WHERE id = %s LIMIT 1",
                (job_id,),
            )
            job = cursor.fetchone()
            return not job or bool(job["cancel_requested"])
    finally:
        connection.close()


def mark_job_completed(job_id, summary, result_path=None, result_size_bytes=0):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'COMPLETED', stage = 'completed', progress = 100,
                    total_score = %s, summary_feedback = %s,
                    processing_time_seconds = %s, metrics = %s, result_path = %s,
                    result_size_bytes = %s,
                    completed_at = NOW(3), last_heartbeat_at = NOW(3)
                WHERE id = %s AND cancel_requested = FALSE
                """,
                (
                    summary.get("total_score"),
                    summary.get("summary_feedback"),
                    summary.get("processing_time_seconds"),
                    json.dumps(summary.get("metrics", {}), ensure_ascii=False),
                    result_path,
                    result_size_bytes,
                    job_id,
                ),
            )
            return cursor.rowcount == 1


def mark_job_failed(job_id, public_error, processing_time_seconds=None):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'FAILED', stage = 'failed', progress = 100,
                    public_error = %s, processing_time_seconds = %s,
                    completed_at = NOW(3), last_heartbeat_at = NOW(3)
                WHERE id = %s AND status = 'PROCESSING'
                """,
                (public_error, processing_time_seconds, job_id),
            )


def mark_job_result_missing(job_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'FAILED', stage = 'failed', progress = 100,
                    public_error = %s, result_size_bytes = 0, completed_at = NOW(3)
                WHERE id = %s AND status = 'COMPLETED'
                """,
                ("분석 결과 파일이 없어 결과를 복구할 수 없습니다. 다시 분석해주세요.", job_id),
            )


def sync_job_summary(job_id, summary):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET total_score = %s, summary_feedback = %s, metrics = %s
                WHERE id = %s AND status = 'COMPLETED'
                """,
                (
                    summary.get("total_score"),
                    summary.get("summary_feedback"),
                    json.dumps(summary.get("metrics", {}), ensure_ascii=False),
                    job_id,
                ),
            )


def mark_job_cancelled(job_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'CANCELLED', stage = 'cancelled', progress = 100,
                    public_error = NULL, completed_at = NOW(3), last_heartbeat_at = NOW(3)
                WHERE id = %s AND status IN ('QUEUED', 'PROCESSING')
                """,
                (job_id,),
            )


def recover_interrupted_jobs():
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'QUEUED', stage = 'queued', progress = 0,
                    public_error = NULL, cancel_requested = FALSE, started_at = NULL,
                    completed_at = NULL, last_heartbeat_at = NULL
                WHERE status = 'PROCESSING' AND attempt_count < max_attempts
                """
            )
            requeued = cursor.rowcount
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'FAILED', stage = 'failed', progress = 100,
                    public_error = %s, completed_at = NOW(3)
                WHERE status = 'PROCESSING' AND attempt_count >= max_attempts
                """,
                ("최대 재시도 횟수를 초과했습니다. 영상을 다시 업로드해주세요.",),
            )
            return {"requeued": requeued, "failed": cursor.rowcount}


def request_user_job_cancel(job_id, user_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT status FROM analysis_jobs WHERE id = %s AND user_id = %s FOR UPDATE",
                (job_id, user_id),
            )
            job = cursor.fetchone()
            if not job:
                return None
            if job["status"] not in ("QUEUED", "PROCESSING"):
                return False
            if job["status"] == "QUEUED":
                cursor.execute(
                    """
                    UPDATE analysis_jobs
                    SET status = 'CANCELLED', stage = 'cancelled', progress = 100,
                        cancel_requested = TRUE, completed_at = NOW(3)
                    WHERE id = %s
                    """,
                    (job_id,),
                )
            else:
                cursor.execute(
                    "UPDATE analysis_jobs SET cancel_requested = TRUE, stage = 'cancelling' WHERE id = %s",
                    (job_id,),
                )
            return True


def retry_user_job(job_id, user_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'QUEUED', stage = 'queued', progress = 0,
                    cancel_requested = FALSE, public_error = NULL,
                    started_at = NULL, completed_at = NULL, last_heartbeat_at = NULL,
                    source_expires_at = DATE_ADD(NOW(3), INTERVAL %s HOUR)
                WHERE id = %s AND user_id = %s
                  AND status IN ('FAILED', 'CANCELLED')
                  AND attempt_count < max_attempts
                  AND saved_filename <> ''
                """,
                (ANALYSIS_SOURCE_RETENTION_HOURS, job_id, user_id),
            )
            return cursor.rowcount == 1


def clear_source_file(job_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE analysis_jobs SET saved_filename = '', source_size_bytes = 0, source_expires_at = NULL WHERE id = %s",
                (job_id,),
            )
