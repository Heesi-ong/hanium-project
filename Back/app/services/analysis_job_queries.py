"""사용자 분석 작업 조회, 목록, 성장 추이, 소스 파일 조회 로직을 담당한다."""

from .analysis_job_common import PUBLIC_JOB_COLUMNS, decode_job, decode_job_cursor, encode_job_cursor
from .database import get_connection, transaction


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
            return decode_job(cursor.fetchone())
    finally:
        connection.close()


def get_user_job(job_id, user_id):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"SELECT {PUBLIC_JOB_COLUMNS} FROM analysis_jobs WHERE id = %s AND user_id = %s LIMIT 1",
                (job_id, user_id),
            )
            return decode_job(cursor.fetchone())
    finally:
        connection.close()


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
    decoded_cursor = decode_job_cursor(cursor) if cursor and cursor_supported else None
    if decoded_cursor:
        cursor_created_at, cursor_id = decoded_cursor
        operator = ">" if sort == "oldest" else "<"
        where.append(f"(created_at {operator} %s OR (created_at = %s AND id {operator} %s))")
        params.extend([cursor_created_at, cursor_created_at, cursor_id])
    order_sql = {
        "oldest": "created_at ASC, id ASC",
        "score_high": "total_score IS NULL ASC, total_score DESC, created_at DESC",
        "score_low": "total_score IS NULL ASC, total_score ASC, created_at DESC",
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
            results = [decode_job(job) for job in cursor.fetchall()]
            has_more = cursor_supported and len(results) > limit
            results = results[:limit]
            next_cursor = encode_job_cursor(results[-1]) if has_more and results else None
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
                SELECT id AS result_id, original_filename, total_score, metrics, created_at, completed_at
                FROM analysis_jobs
                WHERE user_id = %s AND status = 'COMPLETED'
                ORDER BY completed_at DESC, created_at DESC, id DESC
                LIMIT %s
                """,
                (user_id, limit),
            )
            return [decode_job(job) for job in reversed(cursor.fetchall())]
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


def list_all_job_ids():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id FROM analysis_jobs")
            return {row["id"] for row in cursor.fetchall()}
    finally:
        connection.close()
