import time

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pymysql.err import IntegrityError

from ..config import (
    ACCOUNT_DELETION_WAIT_SECONDS,
    COOKIE_SECURE,
    FRAME_DIR,
    RESULT_DIR,
    SESSION_COOKIE_NAME,
    SESSION_TTL_HOURS,
    UPLOAD_DIR,
)
from ..schemas.auth import DeleteAccountRequest, LoginRequest, PasswordRequest, ProfileRequest, RegisterRequest
from ..services.auth_service import (
    create_session,
    get_current_user,
    hash_password,
    normalize_email,
    verify_password,
)
from ..services.database import get_connection, transaction
from ..services.file_cleaner import ensure_file_removed, safe_remove_directory
from ..services.practice_coaching import delete_practice_context
from ..services.rate_limit import enforce_rate_limit
from ..services.result_saver import load_analysis_result
from ..services.storage_usage import get_user_storage_usage

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
    email = validate_email(payload.email)
    enforce_rate_limit(request, "login", limit=20, window_seconds=900)
    enforce_rate_limit(request, "login-email", limit=10, window_seconds=900, identity=email)
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


@router.get("/storage")
def storage_usage(user=Depends(get_current_user)):
    return {"storage": get_user_storage_usage(user["id"])}


@router.get("/export")
def export_user_data(user=Depends(get_current_user)):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, email, display_name, role, status, last_login_at, created_at, updated_at "
                "FROM users WHERE id = %s",
                (user["id"],),
            )
            profile = cursor.fetchone()
            cursor.execute(
                "SELECT id, title, model_id, archived_at, created_at, updated_at "
                "FROM conversations WHERE user_id = %s",
                (user["id"],),
            )
            conversations = cursor.fetchall()
            cursor.execute(
                """
                SELECT m.id, m.conversation_id, m.role, m.content, m.metadata,
                       m.sequence_number, m.created_at
                FROM messages m JOIN conversations c ON c.id = m.conversation_id
                WHERE c.user_id = %s ORDER BY m.conversation_id, m.sequence_number
                """,
                (user["id"],),
            )
            messages = cursor.fetchall()
            cursor.execute(
                """
                SELECT id, status, stage, progress, original_filename, public_error,
                       processing_time_seconds, total_score, summary_feedback, metrics,
                       created_at, started_at, completed_at
                FROM analysis_jobs WHERE user_id = %s ORDER BY created_at
                """,
                (user["id"],),
            )
            jobs = cursor.fetchall()
    finally:
        connection.close()

    detailed_results = [result for job in jobs if (result := load_analysis_result(job["id"])) is not None]
    return {
        "profile": profile,
        "conversations": conversations,
        "messages": messages,
        "analysis_jobs": jobs,
        "analysis_results": detailed_results,
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
            cursor.execute("SELECT password_hash, status FROM users WHERE id = %s FOR UPDATE", (user["id"],))
            record = cursor.fetchone()
            if not record or not verify_password(request.password, record["password_hash"]):
                raise HTTPException(status_code=400, detail="비밀번호가 올바르지 않습니다.")
            if record["status"] != "active":
                raise HTTPException(status_code=409, detail="이미 계정 삭제가 진행 중입니다.")
            cursor.execute("UPDATE users SET status = 'deleting' WHERE id = %s", (user["id"],))
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
                (user["id"],),
            )
            cursor.execute("DELETE FROM user_sessions WHERE user_id = %s", (user["id"],))

    deadline = time.monotonic() + ACCOUNT_DELETION_WAIT_SECONDS
    while True:
        connection = get_connection()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT COUNT(*) AS count FROM analysis_jobs WHERE user_id = %s AND status = 'PROCESSING'",
                    (user["id"],),
                )
                processing_count = int(cursor.fetchone()["count"])
        finally:
            connection.close()
        if processing_count == 0:
            break
        if time.monotonic() >= deadline:
            with transaction() as connection:
                with connection.cursor() as cursor:
                    cursor.execute("UPDATE users SET status = 'active' WHERE id = %s AND status = 'deleting'", (user["id"],))
            raise HTTPException(status_code=409, detail="진행 중인 분석 취소를 확인하지 못했습니다. 잠시 후 다시 시도해주세요.")
        time.sleep(0.2)

    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id, saved_filename FROM analysis_jobs WHERE user_id = %s", (user["id"],))
            jobs = cursor.fetchall()
    finally:
        connection.close()
    failed_paths = []
    for job in jobs:
        if job["saved_filename"] and not ensure_file_removed(UPLOAD_DIR / job["saved_filename"]):
            failed_paths.append(str(UPLOAD_DIR / job["saved_filename"]))
        if not ensure_file_removed(RESULT_DIR / f"{job['id']}.json"):
            failed_paths.append(str(RESULT_DIR / f"{job['id']}.json"))
        frame_dir = FRAME_DIR / job["id"]
        if frame_dir.exists() and not safe_remove_directory(frame_dir, FRAME_DIR):
            failed_paths.append(str(frame_dir))
        if not delete_practice_context(job["id"]):
            failed_paths.append(f"practice_context:{job['id']}")
    if failed_paths:
        with transaction() as connection:
            with connection.cursor() as cursor:
                cursor.execute("UPDATE users SET status = 'active' WHERE id = %s AND status = 'deleting'", (user["id"],))
        raise HTTPException(status_code=500, detail="계정 데이터 파일을 완전히 삭제하지 못했습니다. 다시 시도해주세요.")

    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("DELETE FROM users WHERE id = %s AND status = 'deleting'", (user["id"],))
            if cursor.rowcount != 1:
                raise HTTPException(status_code=404, detail="계정을 찾을 수 없습니다.")
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")
