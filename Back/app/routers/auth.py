"""회원가입, 로그인, 세션, 프로필, 비밀번호, 계정 삭제 API를 담당한다."""

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pymysql.err import IntegrityError

from ..config import (
    COOKIE_SECURE,
    SESSION_COOKIE_NAME,
    SESSION_TTL_HOURS,
)
from ..schemas.auth import DeleteAccountRequest, LoginRequest, PasswordRequest, ProfileRequest, RegisterRequest
from ..services.account_deletion import delete_account_data
from ..services.ai_coaching import load_ai_coaching
from ..services.auth_service import (
    create_session,
    get_current_user,
    hash_password,
    normalize_email,
    verify_password,
)
from ..services.database import get_connection, transaction
from ..services.practice_coaching import build_practice_coaching, load_practice_context
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
    practice_contexts = [
        context for job in jobs if (context := load_practice_context(job["id"], user["id"])) is not None
    ]
    ai_coaching = [
        coaching for job in jobs if (coaching := load_ai_coaching(job["id"], user["id"])) is not None
    ]
    practice_coaching = []
    for result in detailed_results:
        context = load_practice_context(result["result_id"], user["id"])
        if context:
            practice_coaching.append(
                {
                    "result_id": result["result_id"],
                    "coaching": build_practice_coaching(result, context),
                }
            )
    return {
        "profile": profile,
        "conversations": conversations,
        "messages": messages,
        "analysis_jobs": jobs,
        "analysis_results": detailed_results,
        "practice_contexts": practice_contexts,
        "practice_coaching": practice_coaching,
        "ai_coaching": ai_coaching,
    }


@router.put("/password", status_code=204)
def change_password(payload: PasswordRequest, request: Request, user=Depends(get_current_user)):
    enforce_rate_limit(request, "password-change", limit=10, window_seconds=3600, identity=str(user["id"]))
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT password_hash FROM users WHERE id = %s FOR UPDATE", (user["id"],))
            record = cursor.fetchone()
            if not record or not verify_password(payload.current_password, record["password_hash"]):
                raise HTTPException(status_code=400, detail="현재 비밀번호가 올바르지 않습니다.")
            cursor.execute(
                "UPDATE users SET password_hash = %s WHERE id = %s",
                (hash_password(payload.new_password), user["id"]),
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
def delete_account(payload: DeleteAccountRequest, request: Request, response: Response, user=Depends(get_current_user)):
    enforce_rate_limit(request, "account-delete", limit=5, window_seconds=3600, identity=str(user["id"]))
    delete_account_data(user, payload.password)
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")
