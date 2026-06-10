from fastapi import APIRouter, Depends, HTTPException

from ..services.auth_service import get_current_user
from ..services.readiness import get_readiness_status

router = APIRouter(prefix="/api/admin", tags=["Admin"])


def require_admin(user=Depends(get_current_user)):
    if user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="관리자 권한이 필요합니다.")
    return user


@router.get("/status")
def get_admin_status(_admin=Depends(require_admin)):
    return get_readiness_status()
