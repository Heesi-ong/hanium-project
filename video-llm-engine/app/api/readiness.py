import os
from typing import Any, Dict

from fastapi import APIRouter, Depends

from app.api.video_llm_analysis import (
    DEFAULT_VIDEO_MAX_SIZE_MB,
    NVIDIA_DEFAULT_API_BASE_URL,
    NVIDIA_DEFAULT_ASSET_BASE_URL,
    resolve_absolute_http_url,
    resolve_nvidia_timeout_seconds,
    resolve_video_llm_backend,
    resolve_video_llm_enabled,
)
from app.core.security import verify_internal_api_key

router = APIRouter(
    prefix="/api/internal",
    tags=["internal-readiness"],
    dependencies=[Depends(verify_internal_api_key)],
)


@router.get("/readiness")
def readiness() -> Dict[str, Any]:
    video_llm_enabled = resolve_video_llm_enabled()
    nvidia_api_key_configured = bool(os.getenv("NVIDIA_API_KEY", "").strip())
    config_errors = validate_real_model_config(video_llm_enabled, nvidia_api_key_configured)
    real_model_ready = video_llm_enabled and not config_errors
    ready = not video_llm_enabled or real_model_ready
    mode = "MOCK"
    reason = "Video LLM real mode is disabled; mock responses are expected."

    if video_llm_enabled and real_model_ready:
        mode = "REAL"
        reason = "Video LLM real mode is enabled and required credentials are configured."
    elif video_llm_enabled:
        mode = "FALLBACK"
        reason = "; ".join(config_errors) + "; analysis will fall back to mock responses."

    return {
        "service": "video-llm-engine",
        "ready": ready,
        "mode": mode,
        "installedBackend": resolve_video_llm_backend(),
        "realModeRequested": video_llm_enabled,
        "realModelReady": real_model_ready,
        "reason": reason,
    }


def validate_real_model_config(
        video_llm_enabled: bool,
        nvidia_api_key_configured: bool,
) -> list[str]:
    if not video_llm_enabled:
        return []

    errors = []
    if not nvidia_api_key_configured:
        errors.append("VIDEO_LLM_ENABLED=true but NVIDIA_API_KEY is missing")

    try:
        resolve_nvidia_timeout_seconds()
    except RuntimeError:
        errors.append("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS must be a positive number")

    raw_max_size_mb = os.getenv(
        "VIDEO_LLM_MAX_VIDEO_SIZE_MB",
        str(DEFAULT_VIDEO_MAX_SIZE_MB),
    ).strip()
    try:
        max_size_mb = int(raw_max_size_mb)
    except ValueError:
        errors.append("VIDEO_LLM_MAX_VIDEO_SIZE_MB must be a positive integer")
    else:
        if max_size_mb <= 0:
            errors.append("VIDEO_LLM_MAX_VIDEO_SIZE_MB must be a positive integer")

    for name, default_value in (
        ("NVIDIA_API_BASE_URL", NVIDIA_DEFAULT_API_BASE_URL),
        ("NVIDIA_ASSET_API_BASE_URL", NVIDIA_DEFAULT_ASSET_BASE_URL),
    ):
        try:
            resolve_absolute_http_url(name, default_value)
        except RuntimeError:
            errors.append(f"{name} must be an absolute http(s) URL")

    return errors
