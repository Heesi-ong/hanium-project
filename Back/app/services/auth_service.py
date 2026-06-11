import hashlib
import hmac
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import Cookie, Depends, HTTPException

from ..config import SESSION_COOKIE_NAME, SESSION_TTL_HOURS
from .database import get_connection


def normalize_email(email):
    return str(email or "").strip().lower()


def hash_password(password, salt=None):
    password_salt = salt or secrets.token_hex(16)
    derived_key = hashlib.scrypt(
        password.encode("utf-8"),
        salt=password_salt.encode("utf-8"),
        n=16384,
        r=8,
        p=1,
        dklen=64,
    )
    return f"scrypt:{password_salt}:{derived_key.hex()}"


def verify_password(password, stored_hash):
    try:
        algorithm, salt, stored_key = stored_hash.split(":", 2)
        if algorithm != "scrypt":
            return False
        candidate = hash_password(password, salt).split(":", 2)[2]
        return hmac.compare_digest(candidate, stored_key)
    except (AttributeError, ValueError):
        return False


def create_session(connection, user_id):
    token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    expires_at = datetime.now(timezone.utc) + timedelta(hours=SESSION_TTL_HOURS)
    with connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO user_sessions (user_id, token_hash, expires_at) VALUES (%s, %s, %s)",
            (user_id, token_hash, expires_at.replace(tzinfo=None)),
        )
    return token


def get_current_user(session_token: str | None = Cookie(default=None, alias=SESSION_COOKIE_NAME)):
    if not session_token:
        raise HTTPException(status_code=401, detail="로그인이 필요합니다.")

    token_hash = hashlib.sha256(session_token.encode("utf-8")).hexdigest()
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT u.id, u.email, u.display_name, u.role, s.id AS session_id
                FROM user_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = %s
                  AND s.expires_at > NOW(3)
                  AND u.status = 'active'
                LIMIT 1
                """,
                (token_hash,),
            )
            user = cursor.fetchone()
            if not user:
                raise HTTPException(status_code=401, detail="로그인 세션이 만료되었거나 유효하지 않습니다.")
            cursor.execute(
                """
                UPDATE user_sessions SET last_seen_at = NOW(3)
                WHERE id = %s AND last_seen_at < DATE_SUB(NOW(3), INTERVAL 5 MINUTE)
                """,
                (user["session_id"],),
            )
        connection.commit()
        return user
    finally:
        connection.close()


CurrentUser = Depends(get_current_user)


def delete_expired_sessions():
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("DELETE FROM user_sessions WHERE expires_at <= NOW(3)")
            deleted = cursor.rowcount
        connection.commit()
        return deleted
    finally:
        connection.close()
