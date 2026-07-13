from typing import Any, Dict

from fastapi import APIRouter, Depends

from app.api.video_llm_analysis import resolve_video_llm_backend, resolve_video_llm_enabled
from app.core.security import verify_internal_api_key

router = APIRouter(
    prefix="/api/internal",
    tags=["internal-readiness"],
    dependencies=[Depends(verify_internal_api_key)],
)


@router.get("/readiness")
def readiness() -> Dict[str, Any]:
    return {
        "service": "video-llm-engine",
        "ready": True,
        "mode": "REAL" if resolve_video_llm_enabled() else "MOCK",
        "installedBackend": resolve_video_llm_backend(),
    }
