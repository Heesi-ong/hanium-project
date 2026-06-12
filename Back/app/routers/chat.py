import base64
import binascii
import json
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from pydantic import BaseModel, Field

from ..config import CHAT_HISTORY_MESSAGES, MAX_CHAT_CONTENT_CHARS, OLLAMA_MODEL
from ..services.ai_coaching import (
    build_presentation_chat_system_prompt,
    build_structured_coaching_input,
    load_ai_coaching,
)
from ..services.analysis_jobs import get_user_job, list_user_growth
from ..services.auth_service import get_current_user
from ..services.database import advisory_lock, get_connection, transaction
from ..services.ollama_service import chat_with_ollama
from ..services.practice_coaching import (
    PURPOSES,
    build_practice_coaching,
    find_previous_same_series,
    load_practice_context,
)
from ..services.rate_limit import enforce_rate_limit
from ..services.result_saver import load_analysis_result

router = APIRouter(prefix="/api", tags=["Chat"])


class ConversationRequest(BaseModel):
    title: str = Field(default="새 대화", max_length=255)
    system_prompt: str | None = None
    analysis_result_id: str | None = Field(default=None, max_length=64)
    practice_question: str | None = Field(default=None, max_length=500)


class ChatRequest(BaseModel):
    content: object


class ConversationUpdateRequest(BaseModel):
    title: str = Field(min_length=1, max_length=255)


def _encode_cursor(payload):
    return base64.urlsafe_b64encode(json.dumps(payload, default=str).encode()).decode().rstrip("=")


def _decode_cursor(value, required_keys):
    try:
        padded = value + "=" * (-len(value) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode())
        if not all(key in payload for key in required_keys):
            return None
        return payload
    except (ValueError, TypeError, json.JSONDecodeError, binascii.Error, UnicodeDecodeError):
        return None


@router.get("/models")
def models(user=Depends(get_current_user)):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, provider, model_key AS modelKey, display_name AS displayName "
                "FROM gpt_models WHERE is_active = TRUE"
            )
            return {"models": cursor.fetchall()}
    finally:
        connection.close()


@router.get("/conversations")
def conversations(
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    cursor: str | None = None,
    archived: bool = False,
    user=Depends(get_current_user),
):
    decoded_cursor = _decode_cursor(cursor, ("updated_at", "id")) if cursor else None
    cursor_clause = ""
    params = [user["id"], archived, archived]
    if decoded_cursor:
        cursor_clause = "AND (c.updated_at < %s OR (c.updated_at = %s AND c.id < %s))"
        params.extend([decoded_cursor["updated_at"], decoded_cursor["updated_at"], decoded_cursor["id"]])
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT c.id, c.title, c.model_id AS modelId, m.display_name AS modelName,
                       c.created_at AS createdAt, c.updated_at AS updatedAt
                FROM conversations c
                LEFT JOIN gpt_models m ON m.id = c.model_id
                WHERE c.user_id = %s
                  AND ((%s = TRUE AND c.archived_at IS NOT NULL) OR (%s = FALSE AND c.archived_at IS NULL))
                  {cursor_clause}
                ORDER BY c.updated_at DESC, c.id DESC
                LIMIT %s OFFSET %s
                """,
                (*params, limit + 1, 0 if decoded_cursor else offset),
            )
            results = cursor.fetchall()
            has_more = len(results) > limit
            results = results[:limit]
            next_cursor = (
                _encode_cursor({"updated_at": results[-1]["updatedAt"], "id": results[-1]["id"]})
                if has_more and results
                else None
            )
            cursor.execute(
                """
                SELECT COUNT(*) AS count FROM conversations
                WHERE user_id = %s
                  AND ((%s = TRUE AND archived_at IS NOT NULL) OR (%s = FALSE AND archived_at IS NULL))
                """,
                (user["id"], archived, archived),
            )
            return {"conversations": results, "total": cursor.fetchone()["count"], "next_cursor": next_cursor}
    finally:
        connection.close()


@router.post("/conversations", status_code=201)
def create_conversation(request: ConversationRequest, user=Depends(get_current_user)):
    title = request.title.strip() or "새 대화"
    system_prompt = request.system_prompt
    if request.analysis_result_id:
        job = get_user_job(request.analysis_result_id, user["id"])
        if not job:
            raise HTTPException(status_code=404, detail="분석 결과를 찾을 수 없습니다.")
        if job["status"] != "COMPLETED":
            raise HTTPException(status_code=409, detail="분석 완료 후 발표 코칭 대화를 시작할 수 있습니다.")
        result = load_analysis_result(request.analysis_result_id)
        if not result:
            raise HTTPException(status_code=404, detail="분석 결과를 찾을 수 없습니다.")
        context = load_practice_context(request.analysis_result_id, user["id"]) or {
            "purpose": "project",
            "audience": "",
            "target_minutes": PURPOSES["project"]["recommended_minutes"],
            "core_message": "",
            "series_name": "",
        }
        previous = find_previous_same_series(
            list_user_growth(user["id"]), user["id"], request.analysis_result_id, context
        )
        rule_coaching = build_practice_coaching(result, context, previous)
        structured_input = build_structured_coaching_input(result, context, rule_coaching, previous)
        system_prompt = build_presentation_chat_system_prompt(
            request.analysis_result_id,
            structured_input,
            load_ai_coaching(request.analysis_result_id, user["id"]),
            request.practice_question,
        )
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id FROM gpt_models WHERE provider = 'ollama' AND model_key = %s AND is_active = TRUE",
                (OLLAMA_MODEL,),
            )
            model = cursor.fetchone()
            if not model:
                raise HTTPException(status_code=503, detail="활성화된 Ollama 모델이 등록되어 있지 않습니다.")
            cursor.execute(
                "INSERT INTO conversations (user_id, model_id, title, system_prompt) VALUES (%s, %s, %s, %s)",
                (user["id"], model["id"], title, system_prompt),
            )
            conversation_id = cursor.lastrowid
    return {"conversation": {"id": conversation_id, "title": title, "modelId": model["id"]}}


@router.patch("/conversations/{conversation_id}")
def update_conversation(conversation_id: int, request: ConversationUpdateRequest, user=Depends(get_current_user)):
    title = request.title.strip()
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE conversations SET title = %s WHERE id = %s AND user_id = %s AND archived_at IS NULL",
                (title, conversation_id, user["id"]),
            )
            if cursor.rowcount != 1:
                raise HTTPException(status_code=404, detail="대화를 찾을 수 없습니다.")
    return {"conversation": {"id": conversation_id, "title": title}}


@router.post("/conversations/{conversation_id}/archive", status_code=204)
def archive_conversation(conversation_id: int, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE conversations SET archived_at = NOW(3) WHERE id = %s AND user_id = %s",
                (conversation_id, user["id"]),
            )
            if cursor.rowcount != 1:
                raise HTTPException(status_code=404, detail="대화를 찾을 수 없습니다.")


@router.post("/conversations/{conversation_id}/restore", status_code=204)
def restore_conversation(conversation_id: int, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE conversations SET archived_at = NULL WHERE id = %s AND user_id = %s AND archived_at IS NOT NULL",
                (conversation_id, user["id"]),
            )
            if cursor.rowcount != 1:
                raise HTTPException(status_code=404, detail="보관된 대화를 찾을 수 없습니다.")


@router.delete("/conversations/{conversation_id}", status_code=204)
def delete_conversation(conversation_id: int, user=Depends(get_current_user)):
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM conversations WHERE id = %s AND user_id = %s",
                (conversation_id, user["id"]),
            )
            if cursor.rowcount != 1:
                raise HTTPException(status_code=404, detail="대화를 찾을 수 없습니다.")


@router.get("/conversations/{conversation_id}/messages")
def messages(
    conversation_id: int,
    limit: int = Query(default=100, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    cursor: str | None = None,
    user=Depends(get_current_user),
):
    decoded_cursor = _decode_cursor(cursor, ("sequence_number",)) if cursor else None
    cursor_clause = "AND m.sequence_number < %s" if decoded_cursor else ""
    page_params = [conversation_id, user["id"]]
    if decoded_cursor:
        page_params.append(decoded_cursor["sequence_number"])
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id FROM conversations WHERE id = %s AND user_id = %s LIMIT 1",
                (conversation_id, user["id"]),
            )
            if not cursor.fetchone():
                raise HTTPException(status_code=404, detail="대화를 찾을 수 없습니다.")
            cursor.execute(
                f"""
                SELECT * FROM (
                  SELECT m.id, m.role, m.content, m.metadata,
                         m.sequence_number AS sequenceNumber, m.created_at AS createdAt
                  FROM messages m
                  JOIN conversations c ON c.id = m.conversation_id
                  WHERE c.id = %s AND c.user_id = %s
                    {cursor_clause}
                  ORDER BY m.sequence_number DESC
                  LIMIT %s OFFSET %s
                ) recent ORDER BY sequenceNumber
                """,
                (*page_params, limit + 1, 0 if decoded_cursor else offset),
            )
            results = cursor.fetchall()
            has_more = len(results) > limit
            results = results[-limit:]
            next_cursor = (
                _encode_cursor({"sequence_number": results[0]["sequenceNumber"]}) if has_more and results else None
            )
            cursor.execute("SELECT COUNT(*) AS count FROM messages WHERE conversation_id = %s", (conversation_id,))
            return {"messages": results, "total": cursor.fetchone()["count"], "next_cursor": next_cursor}
    finally:
        connection.close()


@router.get("/usage/summary")
def usage_summary(user=Depends(get_current_user)):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT COUNT(*) AS requestCount,
                       COALESCE(SUM(input_tokens), 0) AS inputTokens,
                       COALESCE(SUM(output_tokens), 0) AS outputTokens,
                       COALESCE(SUM(total_tokens), 0) AS totalTokens,
                       COALESCE(SUM(estimated_cost), 0) AS estimatedCost
                FROM gpt_usage
                WHERE user_id = %s
                """,
                (user["id"],),
            )
            return {"usage": cursor.fetchone()}
    finally:
        connection.close()


@router.post("/conversations/{conversation_id}/chat", status_code=201)
def chat(
    conversation_id: int,
    payload: ChatRequest,
    request: Request,
    idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    user=Depends(get_current_user),
):
    enforce_rate_limit(request, "chat", limit=30, window_seconds=900)
    if not isinstance(payload.content, str):
        raise HTTPException(status_code=400, detail="content는 문자열이어야 합니다.")
    content = payload.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="비어 있지 않은 content가 필요합니다.")
    if len(content) > MAX_CHAT_CONTENT_CHARS:
        raise HTTPException(status_code=400, detail=f"content는 {MAX_CHAT_CONTENT_CHARS}자를 초과할 수 없습니다.")

    if idempotency_key and len(idempotency_key) > 255:
        raise HTTPException(status_code=400, detail="Idempotency-Key는 255자를 초과할 수 없습니다.")
    request_id = idempotency_key or str(uuid.uuid4())
    lock_name = f"hanium_conversation_chat_{conversation_id}"
    user_message_id = None
    with advisory_lock(lock_name, 10) as lock_acquired:
        if not lock_acquired:
            raise HTTPException(status_code=409, detail="이 대화의 다른 채팅 요청이 처리 중입니다.")

        try:
            with transaction() as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT id FROM gpt_usage WHERE request_id = %s LIMIT 1", (request_id,))
                    if cursor.fetchone():
                        raise HTTPException(status_code=409, detail="이미 완료된 동일 채팅 요청입니다.")
                    cursor.execute(
                        "SELECT id, system_prompt FROM conversations WHERE id = %s AND user_id = %s FOR UPDATE",
                        (conversation_id, user["id"]),
                    )
                    conversation = cursor.fetchone()
                    if not conversation:
                        raise HTTPException(status_code=404, detail="대화를 찾을 수 없습니다.")
                    cursor.execute(
                        """
                        SELECT id, provider, model_key, display_name FROM gpt_models
                        WHERE provider = 'ollama' AND model_key = %s AND is_active = TRUE LIMIT 1
                        """,
                        (OLLAMA_MODEL,),
                    )
                    model = cursor.fetchone()
                    if not model:
                        raise HTTPException(status_code=503, detail="활성화된 Ollama 모델이 등록되어 있지 않습니다.")
                    cursor.execute(
                        "SELECT COALESCE(MAX(sequence_number), 0) + 1 AS next_sequence "
                        "FROM messages WHERE conversation_id = %s",
                        (conversation_id,),
                    )
                    user_sequence = cursor.fetchone()["next_sequence"]
                    cursor.execute(
                        "INSERT INTO messages (conversation_id, role, content, metadata, sequence_number) "
                        "VALUES (%s, 'user', %s, %s, %s)",
                        (
                            conversation_id,
                            content,
                            json.dumps({"chatStatus": "pending", "requestId": request_id}),
                            user_sequence,
                        ),
                    )
                    user_message_id = cursor.lastrowid
                    cursor.execute(
                        """
                        SELECT role, content FROM (
                          SELECT role, content, sequence_number FROM messages
                          WHERE conversation_id = %s AND role IN ('system', 'user', 'assistant')
                            AND (
                              metadata IS NULL OR
                              JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.chatStatus')) IS NULL OR
                              JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.chatStatus')) <> 'failed'
                            )
                          ORDER BY sequence_number DESC
                          LIMIT %s
                        ) recent
                        ORDER BY sequence_number
                        """,
                        (conversation_id, CHAT_HISTORY_MESSAGES),
                    )
                    history = cursor.fetchall()

            ollama_messages = []
            if conversation["system_prompt"]:
                ollama_messages.append({"role": "system", "content": conversation["system_prompt"]})
            ollama_messages.extend(history)
            result = chat_with_ollama(ollama_messages)

            with transaction() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT id FROM conversations WHERE id = %s AND user_id = %s FOR UPDATE",
                        (conversation_id, user["id"]),
                    )
                    if not cursor.fetchone():
                        raise HTTPException(status_code=404, detail="대화를 찾을 수 없습니다.")
                    cursor.execute(
                        "SELECT COALESCE(MAX(sequence_number), 0) + 1 AS next_sequence "
                        "FROM messages WHERE conversation_id = %s",
                        (conversation_id,),
                    )
                    assistant_sequence = cursor.fetchone()["next_sequence"]
                    cursor.execute(
                        "INSERT INTO messages (conversation_id, role, content, metadata, sequence_number) "
                        "VALUES (%s, 'assistant', %s, %s, %s)",
                        (
                            conversation_id,
                            result["content"],
                            json.dumps(
                                {"chatStatus": "completed", "requestId": request_id, "model": model["model_key"]}
                            ),
                            assistant_sequence,
                        ),
                    )
                    assistant_message_id = cursor.lastrowid
                    cursor.execute(
                        "UPDATE messages SET metadata = JSON_SET(metadata, '$.chatStatus', 'completed') WHERE id = %s",
                        (user_message_id,),
                    )
                    cursor.execute(
                        """
                        INSERT INTO gpt_usage
                          (user_id, conversation_id, message_id, model_id, request_id,
                           input_tokens, output_tokens, total_tokens, estimated_cost)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 0)
                        """,
                        (
                            user["id"],
                            conversation_id,
                            assistant_message_id,
                            model["id"],
                            request_id,
                            result["input_tokens"],
                            result["output_tokens"],
                            result["total_tokens"],
                        ),
                    )
                    cursor.execute("UPDATE conversations SET updated_at = NOW(3) WHERE id = %s", (conversation_id,))

            return {
                "userMessage": {
                    "id": user_message_id,
                    "role": "user",
                    "content": content,
                    "sequenceNumber": user_sequence,
                },
                "assistantMessage": {
                    "id": assistant_message_id,
                    "role": "assistant",
                    "content": result["content"],
                    "sequenceNumber": assistant_sequence,
                },
                "model": {
                    "provider": model["provider"],
                    "modelKey": model["model_key"],
                    "displayName": model["display_name"],
                },
                "usage": {
                    "inputTokens": result["input_tokens"],
                    "outputTokens": result["output_tokens"],
                    "totalTokens": result["total_tokens"],
                    "estimatedCost": 0,
                },
            }
        except Exception as error:
            if user_message_id:
                try:
                    with transaction() as connection:
                        with connection.cursor() as cursor:
                            cursor.execute(
                                "UPDATE messages SET metadata = JSON_SET(metadata, '$.chatStatus', 'failed', "
                                "'$.errorCode', %s) WHERE id = %s",
                                (error.__class__.__name__, user_message_id),
                            )
                except Exception:
                    pass
            raise
