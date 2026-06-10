from pathlib import Path

from ..config import RESULT_DIR, UPLOAD_DIR, USER_MAX_ACTIVE_ANALYSES, USER_STORAGE_QUOTA_MB
from ..services.database import get_connection


def _file_size(path: Path):
    try:
        return path.stat().st_size if path.is_file() else 0
    except OSError:
        return 0


def get_user_storage_usage(user_id):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, saved_filename, status FROM analysis_jobs WHERE user_id = %s",
                (user_id,),
            )
            jobs = cursor.fetchall()
    finally:
        connection.close()

    upload_bytes = sum(_file_size(UPLOAD_DIR / job["saved_filename"]) for job in jobs if job["saved_filename"])
    result_bytes = sum(_file_size(RESULT_DIR / f"{job['id']}.json") for job in jobs)
    active_count = sum(job["status"] in ("QUEUED", "PROCESSING") for job in jobs)
    quota_bytes = USER_STORAGE_QUOTA_MB * 1024 * 1024
    used_bytes = upload_bytes + result_bytes
    return {
        "used_bytes": used_bytes,
        "upload_bytes": upload_bytes,
        "result_bytes": result_bytes,
        "quota_bytes": quota_bytes,
        "available_bytes": max(0, quota_bytes - used_bytes),
        "active_analysis_count": active_count,
        "max_active_analyses": USER_MAX_ACTIVE_ANALYSES,
    }
