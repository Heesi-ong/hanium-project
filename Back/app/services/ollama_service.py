"""Ollama 채팅 API 호출을 공통화하고 실패를 HTTP 오류로 변환한다."""

import requests
from fastapi import HTTPException

from ..config import OLLAMA_BASE_URL, OLLAMA_MODEL, OLLAMA_NUM_CTX, OLLAMA_NUM_PREDICT, OLLAMA_TIMEOUT_SECONDS


def chat_with_ollama(messages, response_format=None, options=None, think=None):
    payload = {"model": OLLAMA_MODEL, "stream": False, "messages": messages}
    if response_format:
        payload["format"] = response_format
    payload["options"] = {
        "num_ctx": OLLAMA_NUM_CTX,
        "num_predict": OLLAMA_NUM_PREDICT,
        **(options or {}),
    }
    if think is not None:
        payload["think"] = think
    try:
        response = requests.post(
            f"{OLLAMA_BASE_URL}/api/chat",
            json=payload,
            timeout=OLLAMA_TIMEOUT_SECONDS,
        )
    except requests.Timeout as error:
        raise HTTPException(status_code=504, detail="Ollama 응답 시간이 초과되었습니다.") from error
    except requests.ConnectionError as error:
        raise HTTPException(status_code=503, detail="Ollama 서버에 연결할 수 없습니다.") from error

    try:
        result = response.json()
    except ValueError as error:
        raise HTTPException(status_code=503, detail="Ollama 응답 형식이 올바르지 않습니다.") from error

    if not response.ok:
        message = str(result.get("error", ""))
        if response.status_code == 404 or "model" in message.lower() and "not found" in message.lower():
            raise HTTPException(status_code=503, detail="설정된 Ollama 모델이 설치되어 있지 않습니다.")
        raise HTTPException(status_code=503, detail="Ollama 요청에 실패했습니다.")

    content = str(result.get("message", {}).get("content", "")).strip()
    input_tokens = result.get("prompt_eval_count")
    output_tokens = result.get("eval_count")
    if not content or not isinstance(input_tokens, int) or not isinstance(output_tokens, int):
        raise HTTPException(status_code=503, detail="Ollama 응답에 메시지 또는 토큰 사용량이 없습니다.")

    return {
        "content": content,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": input_tokens + output_tokens,
    }
