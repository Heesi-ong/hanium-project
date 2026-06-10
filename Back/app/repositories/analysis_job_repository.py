from ..services.database import get_connection


def list_processing_job_ids():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id FROM analysis_jobs WHERE status = 'PROCESSING'")
            return {job["id"] for job in cursor.fetchall()}
    finally:
        connection.close()
