from fastapi import APIRouter, Depends, HTTPException

from ..services.analysis_jobs import list_admin_problem_jobs, retry_admin_job
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


@router.post("/analysis-jobs/{job_id}/retry", status_code=202)
def retry_problem_job(job_id: str, _admin=Depends(require_admin)):
    if not retry_admin_job(job_id):
        raise HTTPException(status_code=409, detail="안전하게 재시도할 수 있는 실패 작업이 아닙니다.")
    return {"message": "analysis requeued"}
