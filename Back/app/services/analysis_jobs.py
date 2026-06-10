import base64
import binascii
import json

from ..config import ANALYSIS_RESULT_RETENTION_DAYS, ANALYSIS_SOURCE_RETENTION_HOURS
from ..services.database import get_connection, transaction

PUBLIC_JOB_COLUMNS = """
    id AS result_id, status, stage, progress, attempt_count, max_attempts,
    original_filename, public_error, processing_time_seconds, total_score,
    summary_feedback, metrics, created_at, started_at, completed_at,
    last_heartbeat_at, (saved_filename <> '' AND status IN ('FAILED','CANCELLED')
    AND attempt_count < max_attempts) AS retry_available
"""


def _decode_job(job):
    if job and isinstance(job.get("metrics"), str):
        job["metrics"] = json.loads(job["metrics"])
    return job


def create_analysis_job(job_id, user_id, original_filename, saved_filename, idempotency_key=None):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO analysis_jobs
                  (id, user_id, idempotency_key, status, stage, progress, original_filename,
                   saved_filename, source_expires_at)
                VALUES (%s, %s, %s, 'QUEUED', 'queued', 0, %s, %s,
                        DATE_ADD(NOW(3), INTERVAL %s HOUR))
                """,
                (
                    job_id,
                    user_id,
                    idempotency_key,
                    original_filename,
                    saved_filename,
                    ANALYSIS_SOURCE_RETENTION_HOURS,
                ),
            )


def get_user_job_by_idempotency_key(user_id, idempotency_key):
    if not idempotency_key:
        return None
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"SELECT {PUBLIC_JOB_COLUMNS} FROM analysis_jobs "
                "WHERE user_id = %s AND idempotency_key = %s LIMIT 1",
                (user_id, idempotency_key),
            )
            return _decode_job(cursor.fetchone())
    finally:
        connection.close()


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


def mark_job_completed(job_id, summary, result_path=None):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = 'COMPLETED', stage = 'completed', progress = 100,
                    total_score = %s, summary_feedback = %s,
                    processing_time_seconds = %s, metrics = %s, result_path = %s,
                    completed_at = NOW(3), last_heartbeat_at = NOW(3)
                WHERE id = %s AND cancel_requested = FALSE
                """,
                (
                    summary.get("total_score"),
                    summary.get("summary_feedback"),
                    summary.get("processing_time_seconds"),
                    json.dumps(summary.get("metrics", {}), ensure_ascii=False),
                    result_path,
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
                    public_error = %s, completed_at = NOW(3)
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


def get_user_job(job_id, user_id):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"SELECT {PUBLIC_JOB_COLUMNS} FROM analysis_jobs WHERE id = %s AND user_id = %s LIMIT 1",
                (job_id, user_id),
            )
            return _decode_job(cursor.fetchone())
    finally:
        connection.close()


def _encode_job_cursor(job):
    payload = json.dumps({"created_at": str(job["created_at"]), "id": job["result_id"]}).encode()
    return base64.urlsafe_b64encode(payload).decode().rstrip("=")


def _decode_job_cursor(cursor):
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode())
        return payload["created_at"], payload["id"]
    except (KeyError, ValueError, TypeError, json.JSONDecodeError, binascii.Error, UnicodeDecodeError):
        return None


def list_user_jobs(user_id, status=None, search="", sort="latest", limit=12, offset=0, cursor=None):
    where = ["user_id = %s"]
    params = [user_id]
    if status in ("QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"):
        where.append("status = %s")
        params.append(status)
    if search:
        where.append("(original_filename LIKE %s OR summary_feedback LIKE %s)")
        pattern = f"%{search}%"
        params.extend([pattern, pattern])
    count_where_sql = " AND ".join(where)
    count_params = list(params)
    where_sql = " AND ".join(where)
    cursor_supported = sort in ("latest", "oldest")
    decoded_cursor = _decode_job_cursor(cursor) if cursor and cursor_supported else None
    if decoded_cursor:
        cursor_created_at, cursor_id = decoded_cursor
        operator = ">" if sort == "oldest" else "<"
        where.append(f"(created_at {operator} %s OR (created_at = %s AND id {operator} %s))")
        params.extend([cursor_created_at, cursor_created_at, cursor_id])
    order_sql = {
        "oldest": "created_at ASC, id ASC",
        "score_high": "total_score DESC, created_at DESC",
        "score_low": "total_score ASC, created_at DESC",
    }.get(sort, "created_at DESC, id DESC")
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT {PUBLIC_JOB_COLUMNS.replace("public_error,", "public_error AS error,")}
                FROM analysis_jobs
                WHERE {where_sql}
                ORDER BY {order_sql}
                LIMIT %s OFFSET %s
                """,
                (*params, limit + 1 if cursor_supported else limit, 0 if cursor_supported else offset),
            )
            results = [_decode_job(job) for job in cursor.fetchall()]
            has_more = cursor_supported and len(results) > limit
            results = results[:limit]
            next_cursor = _encode_job_cursor(results[-1]) if has_more and results else None
            cursor.execute(f"SELECT COUNT(*) AS count FROM analysis_jobs WHERE {count_where_sql}", count_params)
            total = cursor.fetchone()["count"]
            cursor.execute(
                """
                SELECT COUNT(*) AS total,
                       SUM(status = 'COMPLETED') AS completed,
                       SUM(status = 'FAILED') AS failed,
                       SUM(status = 'CANCELLED') AS cancelled,
                       ROUND(AVG(CASE WHEN status = 'COMPLETED' THEN total_score END)) AS average_score
                FROM analysis_jobs WHERE user_id = %s
                """,
                (user_id,),
            )
            summary = cursor.fetchone()
            return {
                "results": results,
                "total": total,
                "summary": summary,
                "next_cursor": next_cursor,
                "cursor_supported": cursor_supported,
            }
    finally:
        connection.close()


def list_user_growth(user_id, limit=20):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id AS result_id, original_filename, total_score, metrics, completed_at
                FROM analysis_jobs
                WHERE user_id = %s AND status = 'COMPLETED'
                ORDER BY completed_at DESC
                LIMIT %s
                """,
                (user_id, limit),
            )
            return [_decode_job(job) for job in reversed(cursor.fetchall())]
    finally:
        connection.close()


def get_user_job_source_filename(job_id, user_id):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT saved_filename FROM analysis_jobs WHERE id = %s AND user_id = %s LIMIT 1",
                (job_id, user_id),
            )
            job = cursor.fetchone()
            return job["saved_filename"] if job else None
    finally:
        connection.close()


def delete_user_job(job_id, user_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM analysis_jobs WHERE id = %s AND user_id = %s",
                (job_id, user_id),
            )
            return cursor.rowcount == 1


def list_expired_source_files():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, saved_filename FROM analysis_jobs
                WHERE saved_filename <> '' AND source_expires_at IS NOT NULL
                  AND source_expires_at <= NOW(3)
                  AND status IN ('FAILED', 'CANCELLED')
                """
            )
            return cursor.fetchall()
    finally:
        connection.close()


def list_expired_result_ids():
    if ANALYSIS_RESULT_RETENTION_DAYS <= 0:
        return []

    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id FROM analysis_jobs
                WHERE status = 'COMPLETED'
                  AND completed_at <= DATE_SUB(NOW(3), INTERVAL %s DAY)
                """,
                (ANALYSIS_RESULT_RETENTION_DAYS,),
            )
            return [job["id"] for job in cursor.fetchall()]
    finally:
        connection.close()


def clear_source_file(job_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE analysis_jobs SET saved_filename = '', source_expires_at = NULL WHERE id = %s",
                (job_id,),
            )


def get_analysis_queue_status():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                  SUM(status = 'QUEUED') AS queued,
                  SUM(status = 'PROCESSING') AS processing,
                  SUM(status = 'FAILED') AS failed
                FROM analysis_jobs
                """
            )
            result = cursor.fetchone() or {}
            return {key: int(result.get(key) or 0) for key in ("queued", "processing", "failed")}
    finally:
        connection.close()


def delete_completed_job(job_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM analysis_jobs WHERE id = %s AND status = 'COMPLETED'",
                (job_id,),
            )
            return cursor.rowcount == 1
