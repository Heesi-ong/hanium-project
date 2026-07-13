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

    return {
        "service": "analysis-engine",
        "ready": all(models.values()),
        "models": models,
    }
