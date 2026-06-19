"""관리자 작업 이력을 감사 로그 테이블에 기록하고 보존 기간이 지난 로그를 정리한다."""

import json

from ..config import ADMIN_AUDIT_RETENTION_DAYS
from .database import transaction


def insert_admin_audit(
    cursor,
    *,
    actor_type,
    actor_identifier,
    action,
    actor_user_id=None,
    target_user_id=None,
    target_email=None,
    metadata=None,
):
    cursor.execute(
        """
        INSERT INTO admin_audit_logs (
          actor_type, actor_user_id, actor_identifier, action,
          target_user_id, target_email, metadata
        ) VALUES (%s, %s, %s, %s, %s, %s, %s)
        """,
        (
            actor_type,
            actor_user_id,
            actor_identifier,
            action,
            target_user_id,
            target_email,
            json.dumps(metadata or {}),
        ),
    )


def delete_expired_admin_audit_logs():
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM admin_audit_logs WHERE created_at < DATE_SUB(NOW(3), INTERVAL %s DAY)",
                (ADMIN_AUDIT_RETENTION_DAYS,),
            )
            return cursor.rowcount
