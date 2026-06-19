"""계정 탈퇴 시 사용자 소유 데이터와 세션을 정책에 맞게 삭제한다."""

import time

from fastapi import HTTPException

from ..config import (
    ACCOUNT_DELETION_WAIT_SECONDS,
    AI_COACHING_DIR,
    FRAME_DIR,
    RESULT_DIR,
    UPLOAD_DIR,
)
from ..services.auth_service import verify_password
from ..services.database import get_connection, transaction
from ..services.deletion_staging import StagedDeletion
from ..services.practice_contexts import PRACTICE_CONTEXT_DIR


def _restore_deleting_user(user_id):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("UPDATE users SET status = 'active' WHERE id = %s AND status = 'deleting'", (user_id,))


def _wait_for_processing_jobs_to_stop(user_id):
    deadline = time.monotonic() + ACCOUNT_DELETION_WAIT_SECONDS
    while True:
        connection = get_connection()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT COUNT(*) AS count FROM analysis_jobs WHERE user_id = %s AND status = 'PROCESSING'",
                    (user_id,),
                )
                processing_count = int(cursor.fetchone()["count"])
        finally:
            connection.close()
        if processing_count == 0:
            return
        if time.monotonic() >= deadline:
            _restore_deleting_user(user_id)
            raise HTTPException(status_code=409, detail="진행 중인 분석 취소를 확인하지 못했습니다. 잠시 후 다시 시도해주세요.")
        time.sleep(0.2)


def _list_user_analysis_jobs(user_id):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id, saved_filename FROM analysis_jobs WHERE user_id = %s", (user_id,))
            return cursor.fetchall()
    finally:
        connection.close()


def _build_account_data_paths(jobs):
    paths = []
    for job in jobs:
        paths.extend(
            [
                RESULT_DIR / f"{job['id']}.json",
                FRAME_DIR / job["id"],
                PRACTICE_CONTEXT_DIR / f"{job['id']}.json",
                AI_COACHING_DIR / f"{job['id']}.json",
            ]
        )
        if job["saved_filename"]:
            paths.append(UPLOAD_DIR / job["saved_filename"])
    return paths


def delete_account_data(user, password):
    user_id = user["id"]
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT password_hash, status FROM users WHERE id = %s FOR UPDATE", (user_id,))
            record = cursor.fetchone()
            if not record or not verify_password(password, record["password_hash"]):
                raise HTTPException(status_code=400, detail="비밀번호가 올바르지 않습니다.")
            if record["status"] != "active":
                raise HTTPException(status_code=409, detail="이미 계정 삭제가 진행 중입니다.")
            cursor.execute("UPDATE users SET status = 'deleting' WHERE id = %s", (user_id,))
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET cancel_requested = TRUE,
                    stage = IF(status = 'QUEUED', 'cancelled', 'cancelling'),
                    progress = IF(status = 'QUEUED', 100, progress),
                    completed_at = IF(status = 'QUEUED', NOW(3), completed_at),
                    status = IF(status = 'QUEUED', 'CANCELLED', status)
                WHERE user_id = %s AND status IN ('QUEUED','PROCESSING')
                """,
                (user_id,),
            )
            cursor.execute("DELETE FROM user_sessions WHERE user_id = %s", (user_id,))

    _wait_for_processing_jobs_to_stop(user_id)
    jobs = _list_user_analysis_jobs(user_id)
    try:
        staged = StagedDeletion(_build_account_data_paths(jobs))
    except (OSError, ValueError) as error:
        _restore_deleting_user(user_id)
        raise HTTPException(status_code=500, detail="계정 데이터 파일을 안전하게 격리하지 못했습니다.") from error

    try:
        with transaction() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE admin_audit_logs
                    SET target_email = NULL,
                        actor_identifier = IF(actor_user_id = %s, CONCAT('deleted-user:', %s), actor_identifier)
                    WHERE target_user_id = %s OR actor_user_id = %s
                    """,
                    (user_id, user_id, user_id, user_id),
                )
                cursor.execute("DELETE FROM users WHERE id = %s AND status = 'deleting'", (user_id,))
                if cursor.rowcount != 1:
                    raise HTTPException(status_code=404, detail="계정을 찾을 수 없습니다.")
    except Exception:
        staged.rollback()
        _restore_deleting_user(user_id)
        raise
    staged.commit()
    staged.purge()
