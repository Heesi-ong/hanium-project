"""CLI 기반 초기 관리자 생성과 관리자 권한 부여·회수 로직을 처리한다."""

from ..config import DB_MIGRATION_CONFIG
from .admin_audit import insert_admin_audit
from .auth_service import hash_password, normalize_email
from .database import transaction


class AdminManagementError(ValueError):
    pass


def get_user_by_email(email):
    normalized_email = normalize_email(email)
    with transaction(DB_MIGRATION_CONFIG) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, email, display_name, role, status FROM users WHERE email = %s LIMIT 1",
                (normalized_email,),
            )
            return cursor.fetchone()


def create_initial_admin(email, display_name, password, actor_identifier):
    normalized_email = normalize_email(email)
    normalized_display_name = str(display_name or "").strip()
    _validate_new_admin(normalized_email, normalized_display_name, password)
    password_hash = hash_password(password)

    with transaction(DB_MIGRATION_CONFIG) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK('manage-initial-admin', 10) AS acquired")
            if cursor.fetchone()["acquired"] != 1:
                raise AdminManagementError("최초 관리자 생성 잠금을 획득하지 못했습니다.")
            cursor.execute("SELECT COUNT(*) AS count FROM users WHERE role = 'admin' FOR UPDATE")
            if cursor.fetchone()["count"]:
                raise AdminManagementError(
                    "이미 관리자 계정이 존재합니다. 기존 사용자 승격 명령을 사용하세요."
                )
            cursor.execute("SELECT id FROM users WHERE email = %s LIMIT 1 FOR UPDATE", (normalized_email,))
            if cursor.fetchone():
                raise AdminManagementError(
                    "동일한 이메일의 계정이 존재합니다. 기존 사용자 승격 명령을 사용하세요."
                )
            cursor.execute(
                """
                INSERT INTO users (email, password_hash, display_name, role)
                VALUES (%s, %s, %s, 'admin')
                """,
                (normalized_email, password_hash, normalized_display_name),
            )
            user_id = cursor.lastrowid
            insert_admin_audit(
                cursor,
                actor_type="cli",
                actor_identifier=actor_identifier,
                action="admin.initial_created",
                target_user_id=user_id,
                target_email=normalized_email,
                metadata={"display_name": normalized_display_name},
            )
    return {
        "id": user_id,
        "email": normalized_email,
        "display_name": normalized_display_name,
        "role": "admin",
        "status": "active",
    }


def promote_user_to_admin(email, actor_identifier):
    normalized_email = normalize_email(email)
    if not normalized_email:
        raise AdminManagementError("대상 이메일이 필요합니다.")

    with transaction(DB_MIGRATION_CONFIG) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, email, display_name, role, status
                FROM users WHERE email = %s LIMIT 1 FOR UPDATE
                """,
                (normalized_email,),
            )
            user = cursor.fetchone()
            if not user:
                raise AdminManagementError("해당 이메일의 사용자를 찾을 수 없습니다.")
            if user["status"] != "active":
                raise AdminManagementError("활성 상태의 사용자만 관리자로 승격할 수 있습니다.")
            if user["role"] == "admin":
                raise AdminManagementError("이미 관리자 권한을 가진 사용자입니다.")

            cursor.execute("UPDATE users SET role = 'admin' WHERE id = %s", (user["id"],))
            insert_admin_audit(
                cursor,
                actor_type="cli",
                actor_identifier=actor_identifier,
                action="admin.user_promoted",
                target_user_id=user["id"],
                target_email=user["email"],
                metadata={"previous_role": user["role"], "new_role": "admin"},
            )
            user["role"] = "admin"
            return user


def _validate_new_admin(email, display_name, password):
    local, separator, domain = email.partition("@")
    if not separator or not local or "." not in domain:
        raise AdminManagementError("올바른 이메일 주소가 필요합니다.")
    if len(display_name) < 2 or len(display_name) > 80:
        raise AdminManagementError("표시 이름은 2~80자여야 합니다.")
    if len(password) < 8 or len(password) > 200:
        raise AdminManagementError("비밀번호는 8~200자여야 합니다.")
