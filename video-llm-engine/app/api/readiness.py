from typing import Any, Dict

from fastapi import APIRouter, Depends

from app.core.security import verify_internal_api_key
from app.core.settings import get_settings

router = APIRouter(
    prefix="/api/internal",
    tags=["internal-readiness"],
    dependencies=[Depends(verify_internal_api_key)],
)


@router.get("/readiness")
def readiness() -> Dict[str, Any]:
    settings = get_settings()
    real_model_ready = settings.enabled
    ready = True
    mode = "MOCK"
    reason = "Video LLM real mode is disabled; mock responses are expected."

    if real_model_ready:
        mode = "REAL"
        reason = "Video LLM real mode is enabled and required credentials are configured."

    return {
        "service": "video-llm-engine",
        "ready": ready,
        "mode": mode,
        "policy": settings.policy,
        "installedBackend": settings.installed_backend,
        "realModeRequested": settings.enabled,
        "realModelReady": real_model_ready,
        "reason": reason,
    }
