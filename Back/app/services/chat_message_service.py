"""채팅 메시지 저장, Ollama 호출, 응답 메시지와 사용량 기록을 하나의 트랜잭션 흐름으로 처리한다."""

import json

from fastapi import HTTPException

from ..config import CHAT_HISTORY_MESSAGES, OLLAMA_MODEL
from .database import advisory_lock, transaction
from .ollama_service import chat_with_ollama


def send_chat_message(conversation_id: int, content: str, request_id: str, user_id: int):
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
                        (conversation_id, user_id),
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
                        (conversation_id, user_id),
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
                            user_id,
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
