import logging
from pathlib import Path
import threading
import time
from typing import Any, Dict

import httpx

from app.core.settings import VideoLlmSettings, get_settings
from app.services import deadline, nvidia_prompt, nvidia_provider, nvidia_response

logger = logging.getLogger("video-llm-engine")

_REAL_MODEL_SEMAPHORE = threading.Semaphore(3)

# NVIDIA 쪽의 일시적 과부하/속도제한(429, 5xx)은 세그먼트 하나 실패로 전체 job을
# 잃기엔 비용이 크므로(이미 성공한 세그먼트의 호출 비용 포함), 같은 세그먼트를
# 한 번 더 시도해봅니다. 인증/입력 오류(4xx 중 429 제외) 등 재시도해도 결과가
# 바뀌지 않는 오류는 그대로 즉시 실패시킵니다.
_TRANSIENT_RETRY_MAX_ATTEMPTS = 3
_TRANSIENT_RETRY_BACKOFF_SECONDS = 2.0
_TRANSIENT_HTTP_STATUS_CODES = frozenset({429, 500, 502, 503, 504})


def configure_runtime(settings: VideoLlmSettings) -> None:
    """프로세스 기동 시 실제 모델의 최대 동시 호출 수를 고정합니다."""
    global _REAL_MODEL_SEMAPHORE
    _REAL_MODEL_SEMAPHORE = threading.Semaphore(settings.real_model_max_concurrency)


def resolve_semaphore_timeout_seconds() -> float:
    return get_settings().real_model_semaphore_timeout_seconds


def call_chat_completion(
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    job_id: str,
    video_path: Path,
    content_type: str,
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
    deadline_monotonic: float | None = None,
) -> Dict[str, Any]:
    """동시 호출 제한 안에서 NVIDIA 요청을 실행하고 모델 JSON만 반환합니다."""
    semaphore_timeout_seconds = deadline.remaining_timeout_seconds(
        deadline_monotonic,
        resolve_semaphore_timeout_seconds(),
        "real_model_semaphore",
    )
    acquired = _REAL_MODEL_SEMAPHORE.acquire(timeout=semaphore_timeout_seconds)
    if not acquired:
        raise RuntimeError(
            "Video LLM 실제 모델 동시 호출 제한에 걸려 "
            f"{semaphore_timeout_seconds}초 안에 순번을 확보하지 못했습니다."
        )

    try:
        provider_result = _execute_chat_completion_with_retry(
            api_key=api_key,
            model=model,
            base_url=base_url,
            asset_base_url=asset_base_url,
            timeout_seconds=timeout_seconds,
            job_id=job_id,
            video_path=video_path,
            content_type=content_type,
            duration_hint_sec=duration_hint_sec,
            sample_fps=sample_fps,
            max_frames=max_frames,
            deadline_monotonic=deadline_monotonic,
        )
        content = nvidia_response.extract_chat_completion_content(
            provider_result.response_json
        )
        return nvidia_response.parse_model_json(content)
    finally:
        _REAL_MODEL_SEMAPHORE.release()


def _execute_chat_completion_with_retry(
    *,
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    job_id: str,
    video_path: Path,
    content_type: str,
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
    deadline_monotonic: float | None,
) -> nvidia_provider.ChatCompletionResult:
    last_error: httpx.HTTPStatusError | None = None

    for attempt in range(1, _TRANSIENT_RETRY_MAX_ATTEMPTS + 1):
        try:
            return nvidia_provider.execute_chat_completion(
                api_key=api_key,
                model=model,
                base_url=base_url,
                asset_base_url=asset_base_url,
                timeout_seconds=timeout_seconds,
                job_id=job_id,
                video_path=video_path,
                content_type=content_type,
                payload_builder=lambda video_input: nvidia_prompt.build_nvidia_chat_completion_payload(
                    duration_hint_sec,
                    sample_fps,
                    max_frames,
                    model,
                    video_input,
                ),
                deadline_monotonic=deadline_monotonic,
            )
        except httpx.HTTPStatusError as exc:
            status_code = exc.response.status_code
            if status_code not in _TRANSIENT_HTTP_STATUS_CODES:
                raise
            last_error = exc
            if attempt == _TRANSIENT_RETRY_MAX_ATTEMPTS:
                break
            logger.warning(
                "NVIDIA_VIDEO_LLM_TRANSIENT_RETRY jobId=%s attempt=%s status=%s",
                job_id,
                attempt,
                status_code,
            )
            # 재시도로 deadline을 넘기지 않는지 먼저 확인한 뒤 대기합니다.
            deadline.ensure_within(deadline_monotonic, "nvidia_transient_retry_backoff")
            time.sleep(_TRANSIENT_RETRY_BACKOFF_SECONDS)

    raise last_error
