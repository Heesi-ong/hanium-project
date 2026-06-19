"""분석 결과 삭제 요청을 처리하고 연결된 파일과 대화 데이터를 안전하게 정리한다."""

from fastapi import APIRouter, Depends, HTTPException

from ..config import AI_COACHING_DIR, FRAME_DIR, RESULT_DIR, UPLOAD_DIR
from ..services.analysis_jobs import get_user_job_source_filename
from ..services.auth_service import get_current_user
from ..services.conversation_contexts import delete_result_records
from ..services.deletion_staging import StagedDeletion
from ..services.practice_contexts import PRACTICE_CONTEXT_DIR
from .analyze_common import require_owned_job

router = APIRouter(prefix="/analyze", tags=["Analyze"])


@router.delete("/result/{result_id}")
def delete_result(result_id: str, user=Depends(get_current_user)):
    job = require_owned_job(result_id, user["id"])
    if job["status"] in ("QUEUED", "PROCESSING"):
        raise HTTPException(status_code=409, detail="대기 또는 분석 중인 작업은 먼저 취소해주세요.")

    saved_filename = get_user_job_source_filename(result_id, user["id"])
    paths = [
        RESULT_DIR / f"{result_id}.json",
        FRAME_DIR / result_id,
        PRACTICE_CONTEXT_DIR / f"{result_id}.json",
        AI_COACHING_DIR / f"{result_id}.json",
    ]
    if saved_filename:
        paths.append(UPLOAD_DIR / saved_filename)
    try:
        staged = StagedDeletion(paths)
    except (OSError, ValueError) as error:
        raise HTTPException(status_code=500, detail="분석 데이터 파일을 안전하게 격리하지 못했습니다.") from error
    try:
        deleted_conversations = delete_result_records(result_id, user["id"])
    except Exception as error:
        staged.rollback()
        raise HTTPException(status_code=500, detail="분석 데이터 DB 레코드를 삭제하지 못했습니다.") from error
    staged.commit()
    staged.purge()
    return {
        "message": "result deleted",
        "deleted_job": True,
        "deleted_conversations": deleted_conversations,
    }
