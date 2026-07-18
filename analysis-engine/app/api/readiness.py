from typing import Any, Dict

from fastapi import APIRouter, Depends

from app.core import model_registry
from app.core.security import verify_internal_api_key

router = APIRouter(
    prefix="/api/internal",
    tags=["internal-readiness"],
    dependencies=[Depends(verify_internal_api_key)],
)


@router.get("/readiness")
def readiness() -> Dict[str, Any]:
    models = model_registry.model_status()
    missing_models = [name for name, loaded in models.items() if not loaded]
    ready = not missing_models
    reason = "All analysis models are loaded."

    if missing_models:
        reason = "Analysis models are not loaded: " + ", ".join(missing_models)

    return {
        "service": "analysis-engine",
        "ready": ready,
        "models": models,
        "reason": reason,
    }
