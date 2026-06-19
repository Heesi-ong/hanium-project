"""분석 작업 서비스에서 공유하는 컬럼 목록, JSON 디코딩, 커서 헬퍼를 제공한다."""

import base64
import binascii
import json

PUBLIC_JOB_COLUMNS = """
    id AS result_id, status, stage, progress, attempt_count, max_attempts,
    original_filename, public_error, processing_time_seconds, total_score,
    summary_feedback, metrics, created_at, started_at, completed_at,
    last_heartbeat_at, (saved_filename <> '' AND status IN ('FAILED','CANCELLED')
    AND attempt_count < max_attempts) AS retry_available
"""


def decode_job(job):
    if job and isinstance(job.get("metrics"), str):
        job["metrics"] = json.loads(job["metrics"])
    return job


def encode_job_cursor(job):
    payload = json.dumps({"created_at": str(job["created_at"]), "id": job["result_id"]}).encode()
    return base64.urlsafe_b64encode(payload).decode().rstrip("=")


def decode_job_cursor(cursor):
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode())
        return payload["created_at"], payload["id"]
    except (KeyError, ValueError, TypeError, json.JSONDecodeError, binascii.Error, UnicodeDecodeError):
        return None
