"""관리자 대시보드, 사용자 상태 관리, 시스템 상태, 실패 작업 재시도 API를 제공한다."""

from fastapi import APIRouter, Depends, HTTPException, Query

from ..services.admin_users import get_admin_user_metrics, list_admin_users, update_regular_user_status
from ..services.analysis_jobs import get_admin_analysis_metrics, list_admin_problem_jobs, retry_admin_job
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


@router.get("/analysis-jobs")
def get_problem_jobs(limit: int = 50, _admin=Depends(require_admin)):
    if not 1 <= limit <= 100:
        raise HTTPException(status_code=400, detail="조회 개수는 1~100 사이여야 합니다.")
    return {"jobs": list_admin_problem_jobs(limit)}


@router.get("/metrics")
def get_admin_metrics(_admin=Depends(require_admin)):
    return {"analysis": get_admin_analysis_metrics(), "users": get_admin_user_metrics()}


@router.get("/users")
def get_users(
    status: str | None = Query(default=None, pattern="^(active|disabled)$"),
    search: str = Query(default="", max_length=255),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    _admin=Depends(require_admin),
):
    return list_admin_users(status=status, search=search.strip(), limit=limit, offset=offset)


@router.patch("/users/{user_id}/status")
def update_user_status(
    user_id: int,
    status: str = Query(pattern="^(active|disabled)$"),
    admin=Depends(require_admin),
):
    outcome = update_regular_user_status(user_id, status, admin)
    if outcome == "not_found":
        raise HTTPException(status_code=404, detail="사용자를 찾을 수 없습니다.")
    if outcome == "admin_forbidden":
        raise HTTPException(status_code=403, detail="관리자 계정 상태는 웹에서 변경할 수 없습니다.")
    return {"message": "user status updated", "outcome": outcome}


@router.post("/analysis-jobs/{job_id}/retry", status_code=202)
def retry_problem_job(job_id: str, admin=Depends(require_admin)):
    if not retry_admin_job(job_id, admin):
        raise HTTPException(status_code=409, detail="안전하게 재시도할 수 있는 실패 작업이 아닙니다.")
    return {"message": "analysis requeued"}
