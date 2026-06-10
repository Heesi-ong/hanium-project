from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pymysql.err import IntegrityError

from ..config import COOKIE_SECURE, FRAME_DIR, RESULT_DIR, SESSION_COOKIE_NAME, SESSION_TTL_HOURS, UPLOAD_DIR
from ..schemas.auth import DeleteAccountRequest, LoginRequest, PasswordRequest, ProfileRequest, RegisterRequest
from ..services.auth_service import (
    create_session,
    get_current_user,
    hash_password,
    normalize_email,
    verify_password,
)
from ..services.database import transaction
from ..services.file_cleaner import safe_remove_directory, safe_remove_file
from ..services.rate_limit import enforce_rate_limit

router = APIRouter(prefix="/api/auth", tags=["Auth"])


def validate_email(email):
    normalized = normalize_email(email)
    local, separator, domain = normalized.partition("@")
    if not separator or not local or "." not in domain:
        raise HTTPException(status_code=400, detail="올바른 이메일 주소가 필요합니다.")
    return normalized


def set_session_cookie(response, token):
    response.set_cookie(
        key=SESSION_COOKIE_NAME,
        value=token,
        max_age=SESSION_TTL_HOURS * 60 * 60,
        httponly=True,
        secure=COOKIE_SECURE,
        samesite="lax",
        path="/",
    )


@router.post("/register", status_code=201)
def register(payload: RegisterRequest, response: Response, request: Request):
    enforce_rate_limit(request, "register", limit=10, window_seconds=3600)
    email = validate_email(payload.email)
    display_name = payload.display_name.strip()
    if len(display_name) < 2:
        raise HTTPException(status_code=400, detail="표시 이름은 2자 이상이어야 합니다.")
    try:
        with transaction() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO users (email, password_hash, display_name) VALUES (%s, %s, %s)",
                    (email, hash_password(payload.password), display_name),
                )
                user_id = cursor.lastrowid
            token = create_session(connection, user_id)
    except IntegrityError as error:
        raise HTTPException(status_code=409, detail="이미 가입된 이메일입니다.") from error

    set_session_cookie(response, token)
    return {
        "user": {
            "id": user_id,
            "email": email,
            "displayName": display_name,
            "role": "user",
        }
    }


@router.post("/login")
def login(payload: LoginRequest, response: Response, request: Request):
    enforce_rate_limit(request, "login", limit=20, window_seconds=900)
    email = validate_email(payload.email)
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, email, password_hash, display_name, role
                FROM users WHERE email = %s AND status = 'active' LIMIT 1
                """,
                (email,),
            )
            user = cursor.fetchone()
            if not user or not verify_password(payload.password, user["password_hash"]):
                raise HTTPException(status_code=401, detail="이메일 또는 비밀번호가 올바르지 않습니다.")
            cursor.execute("UPDATE users SET last_login_at = NOW(3) WHERE id = %s", (user["id"],))
        token = create_session(connection, user["id"])

    set_session_cookie(response, token)
    return {
        "user": {
            "id": user["id"],
            "email": user["email"],
            "displayName": user["display_name"],
            "role": user["role"],
        }
    }


@router.post("/logout", status_code=204)
def logout(response: Response, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("DELETE FROM user_sessions WHERE id = %s", (user["session_id"],))
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")


@router.get("/me")
def me(user=Depends(get_current_user)):
    return {
        "user": {
            "id": user["id"],
            "email": user["email"],
            "displayName": user["display_name"],
            "role": user["role"],
        }
    }


@router.put("/password", status_code=204)
def change_password(request: PasswordRequest, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT password_hash FROM users WHERE id = %s FOR UPDATE", (user["id"],))
            record = cursor.fetchone()
            if not record or not verify_password(request.current_password, record["password_hash"]):
                raise HTTPException(status_code=400, detail="현재 비밀번호가 올바르지 않습니다.")
            cursor.execute(
                "UPDATE users SET password_hash = %s WHERE id = %s",
                (hash_password(request.new_password), user["id"]),
            )
            cursor.execute(
                "DELETE FROM user_sessions WHERE user_id = %s AND id <> %s",
                (user["id"], user["session_id"]),
            )


@router.put("/profile")
def update_profile(request: ProfileRequest, user=Depends(get_current_user)):
    display_name = request.display_name.strip()
    if len(display_name) < 2:
        raise HTTPException(status_code=400, detail="표시 이름은 2자 이상이어야 합니다.")
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("UPDATE users SET display_name = %s WHERE id = %s", (display_name, user["id"]))
    return {
        "user": {
            "id": user["id"],
            "email": user["email"],
            "displayName": display_name,
            "role": user["role"],
        }
    }


@router.post("/logout-all", status_code=204)
def logout_all(response: Response, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("DELETE FROM user_sessions WHERE user_id = %s", (user["id"],))
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")


@router.delete("/account", status_code=204)
def delete_account(request: DeleteAccountRequest, response: Response, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT password_hash FROM users WHERE id = %s FOR UPDATE", (user["id"],))
            record = cursor.fetchone()
            if not record or not verify_password(request.password, record["password_hash"]):
                raise HTTPException(status_code=400, detail="비밀번호가 올바르지 않습니다.")
            cursor.execute("SELECT id, saved_filename FROM analysis_jobs WHERE user_id = %s", (user["id"],))
            jobs = cursor.fetchall()
            cursor.execute("DELETE FROM users WHERE id = %s", (user["id"],))
    for job in jobs:
        safe_remove_file(UPLOAD_DIR / job["saved_filename"]) if job["saved_filename"] else None
        safe_remove_file(RESULT_DIR / f"{job['id']}.json")
        safe_remove_directory(FRAME_DIR / job["id"], FRAME_DIR)
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")
