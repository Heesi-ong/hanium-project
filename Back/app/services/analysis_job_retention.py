"""분석 작업의 원본 영상과 완료 결과 보존 기간 관련 조회·삭제 로직을 담당한다."""

from ..config import ANALYSIS_RESULT_RETENTION_DAYS
from .database import get_connection, transaction


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


def delete_completed_job(job_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM analysis_jobs WHERE id = %s AND status = 'COMPLETED'",
                (job_id,),
            )
            return cursor.rowcount == 1
