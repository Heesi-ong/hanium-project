"""영상 업로드, 분석 작업 예약, 취소, 재시도 API를 담당한다."""

import logging
import os
import shutil
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Header, HTTPException, Request, UploadFile

from ..config import MAX_UPLOAD_MB, MIN_FREE_DISK_MB, UPLOAD_DIR, USER_MAX_ACTIVE_ANALYSES
from ..services.analysis_jobs import (
    get_user_job_by_idempotency_key,
    get_user_job_source_filename,
    request_user_job_cancel,
    reserve_analysis_job,
    retry_user_job,
)
from ..services.auth_service import get_current_user
from ..services.file_cleaner import safe_remove_file
from ..services.rate_limit import enforce_rate_limit
from ..services.readiness import get_disk_status
from ..services.storage_usage import get_user_storage_usage
from ..services.video_info import get_video_info, validate_video_info
from .analyze_common import require_owned_job

router = APIRouter(prefix="/analyze", tags=["Analyze"])
logger = logging.getLogger(__name__)

ALLOWED_EXTENSIONS = [".mp4", ".mov", ".avi", ".mkv"]
MAX_FILE_SIZE_MB = MAX_UPLOAD_MB
MAX_FILE_SIZE = MAX_FILE_SIZE_MB * 1024 * 1024

os.makedirs(UPLOAD_DIR, exist_ok=True)


@router.get("/")
def analyze_home(user=Depends(get_current_user)):
    return {"message": "Analyze API ready", "userId": user["id"]}


@router.post("/upload", status_code=202)
def upload_video(
    request: Request,
    file: UploadFile = File(...),
    idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    user=Depends(get_current_user),
):
    enforce_rate_limit(request, "analysis-upload", limit=10, window_seconds=3600)
    if idempotency_key and len(idempotency_key) > 255:
        raise HTTPException(status_code=400, detail="Idempotency-Key는 255자를 초과할 수 없습니다.")
    existing_job = get_user_job_by_idempotency_key(user["id"], idempotency_key)
    if existing_job:
        return {"message": "existing analysis job", "job": existing_job}
    original_filename = os.path.basename(file.filename or "video")
    ext = os.path.splitext(original_filename)[1].lower()

    if ext not in ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail="지원하지 않는 영상 형식입니다. MP4, MOV, AVI, MKV 파일을 선택해주세요.",
        )

    disk_status = get_disk_status()
    required_free_mb = MIN_FREE_DISK_MB + MAX_FILE_SIZE_MB
    if not disk_status["ok"] or disk_status["free_mb"] < required_free_mb:
        raise HTTPException(
            status_code=507,
            detail="서버 저장 공간이 부족하여 지금은 영상을 업로드할 수 없습니다.",
        )

    file.file.seek(0, os.SEEK_END)
    file_size = file.file.tell()
    file.file.seek(0)
    if file_size <= 0:
        raise HTTPException(status_code=400, detail="비어 있는 영상 파일은 업로드할 수 없습니다.")
    if file_size > MAX_FILE_SIZE:
        raise HTTPException(status_code=400, detail=f"파일 크기는 {MAX_FILE_SIZE_MB}MB 이하여야 합니다.")

    storage = get_user_storage_usage(user["id"])
    if storage["active_analysis_count"] >= USER_MAX_ACTIVE_ANALYSES:
        raise HTTPException(
            status_code=409,
            detail=f"동시에 진행할 수 있는 분석은 최대 {USER_MAX_ACTIVE_ANALYSES}개입니다.",
        )
    if file_size > storage["available_bytes"]:
        raise HTTPException(status_code=413, detail="사용자 저장 공간이 부족합니다. 기존 분석 결과를 삭제해주세요.")

    job_id = str(uuid4())
    saved_filename = f"{job_id}{ext}"
    file_path = UPLOAD_DIR / saved_filename

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        try:
            validate_video_info(get_video_info(str(file_path)))
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        reservation = reserve_analysis_job(
            job_id,
            user["id"],
            original_filename,
            saved_filename,
            file_size,
            idempotency_key=idempotency_key,
        )
        if reservation["outcome"] == "existing":
            safe_remove_file(file_path)
            return {"message": "existing analysis job", "job": reservation["job"]}
        if reservation["outcome"] == "inactive":
            raise HTTPException(status_code=409, detail="계정 상태가 변경되어 분석을 시작할 수 없습니다.")
        if reservation["outcome"] == "active_limit":
            raise HTTPException(
                status_code=409,
                detail=f"동시에 진행할 수 있는 분석은 최대 {USER_MAX_ACTIVE_ANALYSES}개입니다.",
            )
        if reservation["outcome"] == "quota_limit":
            raise HTTPException(status_code=413, detail="사용자 저장 공간이 부족합니다. 기존 분석 결과를 삭제해주세요.")
    except HTTPException:
        safe_remove_file(file_path)
        raise
    except Exception:
        logger.exception(
            "Failed to create analysis job: result_id=%s filename=%s",
            job_id,
            original_filename,
        )
        safe_remove_file(file_path)
        raise HTTPException(status_code=500, detail="분석 작업을 생성하지 못했습니다.") from None

    return {
        "message": "analysis queued",
        "job": {
            "result_id": job_id,
            "status": "QUEUED",
            "original_filename": original_filename,
        },
    }


@router.get("/job/{result_id}")
def get_job_status(result_id: str, user=Depends(get_current_user)):
    return {"job": require_owned_job(result_id, user["id"])}


@router.post("/job/{result_id}/cancel")
def cancel_job(result_id: str, user=Depends(get_current_user)):
    result = request_user_job_cancel(result_id, user["id"])
    if result is None:
        raise HTTPException(status_code=404, detail="분석 작업을 찾을 수 없습니다.")
    if result is False:
        raise HTTPException(status_code=409, detail="대기 또는 처리 중인 작업만 취소할 수 있습니다.")
    return {"message": "cancel requested", "job": require_owned_job(result_id, user["id"])}


@router.post("/job/{result_id}/retry", status_code=202)
def retry_job(result_id: str, user=Depends(get_current_user)):
    require_owned_job(result_id, user["id"])
    saved_filename = get_user_job_source_filename(result_id, user["id"])
    if not saved_filename or not (UPLOAD_DIR / saved_filename).exists():
        raise HTTPException(status_code=409, detail="재시도할 원본 영상이 보존되어 있지 않습니다.")
    if not retry_user_job(result_id, user["id"]):
        raise HTTPException(status_code=409, detail="재시도 가능한 실패 또는 취소 작업이 아닙니다.")
    return {"message": "analysis requeued", "job": require_owned_job(result_id, user["id"])}
