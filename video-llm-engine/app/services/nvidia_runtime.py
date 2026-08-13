from pathlib import Path
import threading
from typing import Any, Dict

from app.core.settings import VideoLlmSettings, get_settings
from app.services import deadline, nvidia_prompt, nvidia_provider, nvidia_response


_REAL_MODEL_SEMAPHORE = threading.Semaphore(3)


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
        provider_result = nvidia_provider.execute_chat_completion(
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
        content = nvidia_response.extract_chat_completion_content(
            provider_result.response_json
        )
        return nvidia_response.parse_model_json(content)
    finally:
        _REAL_MODEL_SEMAPHORE.release()
