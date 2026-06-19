"""관리자 화면의 사용자 목록, 사용자 통계, 일반 사용자 활성화 상태 변경을 처리한다."""

from .admin_audit import insert_admin_audit
from .database import get_connection, transaction


def get_admin_user_metrics():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT COUNT(*) AS total,
                       SUM(status = 'active') AS active,
                       SUM(status = 'disabled') AS disabled,
                       SUM(role = 'admin') AS admins,
                       SUM(created_at >= DATE_SUB(NOW(3), INTERVAL 24 HOUR)) AS created_last_24_hours
                FROM users
                WHERE status <> 'deleting'
                """
            )
            result = cursor.fetchone() or {}
            return {
                key: int(result.get(key) or 0)
                for key in ("total", "active", "disabled", "admins", "created_last_24_hours")
            }
    finally:
        connection.close()


def list_admin_users(*, status=None, search="", limit=50, offset=0):
    conditions = ["status <> 'deleting'"]
    params = []
    if status:
        conditions.append("status = %s")
        params.append(status)
    if search:
        conditions.append("email LIKE %s")
        params.append(f"%{search}%")
    where = " AND ".join(conditions)
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"SELECT COUNT(*) AS count FROM users WHERE {where}", tuple(params))
            total = int(cursor.fetchone()["count"])
            cursor.execute(
                f"""
                SELECT id, email, status, created_at, (role <> 'admin') AS status_change_allowed
                FROM users
                WHERE {where}
                ORDER BY created_at DESC, id DESC
                LIMIT %s OFFSET %s
                """,
                (*params, limit, offset),
            )
            return {"users": cursor.fetchall(), "total": total}
    finally:
        connection.close()


def update_regular_user_status(user_id, status, admin):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, email, role, status FROM users WHERE id = %s LIMIT 1 FOR UPDATE",
                (user_id,),
            )
            user = cursor.fetchone()
            if not user or user["status"] == "deleting":
                return "not_found"
            if user["role"] == "admin":
                return "admin_forbidden"
            if user["status"] == status:
                return "unchanged"
            previous_status = user["status"]
            cursor.execute("UPDATE users SET status = %s WHERE id = %s", (status, user_id))
            if status == "disabled":
                cursor.execute("DELETE FROM user_sessions WHERE user_id = %s", (user_id,))
            insert_admin_audit(
                cursor,
                actor_type="user",
                actor_user_id=admin["id"],
                actor_identifier=admin["email"],
                action=f"user.{status}",
                target_user_id=user_id,
                target_email=user["email"],
                metadata={"previous_status": previous_status, "new_status": status},
            )
            return "updated"
