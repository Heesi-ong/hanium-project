import json
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, Field

from ..config import CHAT_HISTORY_MESSAGES, MAX_CHAT_CONTENT_CHARS, OLLAMA_MODEL
from ..services.auth_service import get_current_user
from ..services.database import get_connection, transaction
from ..services.ollama_service import chat_with_ollama
from ..services.rate_limit import enforce_rate_limit

router = APIRouter(prefix="/api", tags=["Chat"])


class ConversationRequest(BaseModel):
    title: str = Field(default="새 대화", max_length=255)
    system_prompt: str | None = None


class ChatRequest(BaseModel):
    content: object


class ConversationUpdateRequest(BaseModel):
    title: str = Field(min_length=1, max_length=255)


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
    user=Depends(get_current_user),
):
    connection = get_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT c.id, c.title, c.model_id AS modelId, m.display_name AS modelName,
                       c.created_at AS createdAt, c.updated_at AS updatedAt
                FROM conversations c
                LEFT JOIN gpt_models m ON m.id = c.model_id
                WHERE c.user_id = %s AND c.archived_at IS NULL
                ORDER BY c.updated_at DESC
                LIMIT %s OFFSET %s
                """,
                (user["id"], limit, offset),
            )
            results = cursor.fetchall()
            cursor.execute(
                "SELECT COUNT(*) AS count FROM conversations WHERE user_id = %s AND archived_at IS NULL",
                (user["id"],),
            )
            return {"conversations": results, "total": cursor.fetchone()["count"]}
    finally:
        connection.close()


@router.post("/conversations", status_code=201)
def create_conversation(request: ConversationRequest, user=Depends(get_current_user)):
    title = request.title.strip() or "새 대화"
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
                (user["id"], model["id"], title, request.system_prompt),
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
    user=Depends(get_current_user),
):
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
                """
                SELECT m.id, m.role, m.content, m.metadata,
                       m.sequence_number AS sequenceNumber, m.created_at AS createdAt
                FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE c.id = %s AND c.user_id = %s
                ORDER BY m.sequence_number
                LIMIT %s OFFSET %s
                """,
                (conversation_id, user["id"], limit, offset),
            )
            results = cursor.fetchall()
            cursor.execute("SELECT COUNT(*) AS count FROM messages WHERE conversation_id = %s", (conversation_id,))
            return {"messages": results, "total": cursor.fetchone()["count"]}
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
def chat(conversation_id: int, payload: ChatRequest, request: Request, user=Depends(get_current_user)):
    enforce_rate_limit(request, "chat", limit=30, window_seconds=900)
    if not isinstance(payload.content, str):
        raise HTTPException(status_code=400, detail="content는 문자열이어야 합니다.")
    content = payload.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="비어 있지 않은 content가 필요합니다.")
    if len(content) > MAX_CHAT_CONTENT_CHARS:
        raise HTTPException(status_code=400, detail=f"content는 {MAX_CHAT_CONTENT_CHARS}자를 초과할 수 없습니다.")

    connection = get_connection()
    request_id = str(uuid.uuid4())
    lock_name = f"hanium_conversation_chat_{conversation_id}"
    user_message_id = None
    lock_acquired = False
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK(%s, 10) AS acquired", (lock_name,))
            lock_acquired = cursor.fetchone()["acquired"] == 1
        if not lock_acquired:
            raise HTTPException(status_code=409, detail="이 대화의 다른 채팅 요청이 처리 중입니다.")

        connection.begin()
        with connection.cursor() as cursor:
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
                (conversation_id, content, json.dumps({"chatStatus": "pending", "requestId": request_id}), user_sequence),
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
        connection.commit()

        ollama_messages = []
        if conversation["system_prompt"]:
            ollama_messages.append({"role": "system", "content": conversation["system_prompt"]})
        ollama_messages.extend(history)
        result = chat_with_ollama(ollama_messages)

        connection.begin()
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
                    json.dumps({"chatStatus": "completed", "requestId": request_id, "model": model["model_key"]}),
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
                    user["id"], conversation_id, assistant_message_id, model["id"], request_id,
                    result["input_tokens"], result["output_tokens"], result["total_tokens"],
                ),
            )
            cursor.execute("UPDATE conversations SET updated_at = NOW(3) WHERE id = %s", (conversation_id,))
        connection.commit()

        return {
            "userMessage": {"id": user_message_id, "role": "user", "content": content, "sequenceNumber": user_sequence},
            "assistantMessage": {
                "id": assistant_message_id,
                "role": "assistant",
                "content": result["content"],
                "sequenceNumber": assistant_sequence,
            },
            "model": {"provider": model["provider"], "modelKey": model["model_key"], "displayName": model["display_name"]},
            "usage": {
                "inputTokens": result["input_tokens"],
                "outputTokens": result["output_tokens"],
                "totalTokens": result["total_tokens"],
                "estimatedCost": 0,
            },
        }
    except Exception as error:
        connection.rollback()
        if user_message_id:
            try:
                connection.begin()
                with connection.cursor() as cursor:
                    cursor.execute(
                        "UPDATE messages SET metadata = JSON_SET(metadata, '$.chatStatus', 'failed', '$.errorCode', %s) "
                        "WHERE id = %s",
                        (error.__class__.__name__, user_message_id),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
        raise
    finally:
        if lock_acquired:
            try:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT RELEASE_LOCK(%s)", (lock_name,))
            except Exception:
                pass
        connection.close()
