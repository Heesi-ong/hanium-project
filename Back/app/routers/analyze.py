import os
import shutil
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import PlainTextResponse

from ..config import MAX_UPLOAD_MB, MIN_FREE_DISK_MB, RESULT_DIR, UPLOAD_DIR, USER_MAX_ACTIVE_ANALYSES
from ..services.analysis_jobs import (
    delete_user_job,
    get_user_job,
    get_user_job_by_idempotency_key,
    get_user_job_source_filename,
    list_user_growth,
    list_user_jobs,
    mark_job_result_missing,
    request_user_job_cancel,
    reserve_analysis_job,
    retry_user_job,
    sync_job_summary,
)
from ..services.auth_service import get_current_user
from ..services.file_cleaner import ensure_file_removed, safe_remove_file
from ..services.practice_coaching import delete_practice_context
from ..services.rate_limit import enforce_rate_limit
from ..services.readiness import get_disk_status
from ..services.result_saver import delete_analysis_result, load_analysis_result
from ..services.storage_usage import get_user_storage_usage
from ..services.video_info import get_video_info, validate_video_info

router = APIRouter(prefix="/analyze", tags=["Analyze"])

ALLOWED_EXTENSIONS = [".mp4", ".mov", ".avi", ".mkv"]
MAX_FILE_SIZE_MB = MAX_UPLOAD_MB
MAX_FILE_SIZE = MAX_FILE_SIZE_MB * 1024 * 1024

os.makedirs(UPLOAD_DIR, exist_ok=True)


def _require_owned_job(result_id, user_id):
    job = get_user_job(result_id, user_id)
    if not job:
        raise HTTPException(status_code=404, detail="분석 결과를 찾을 수 없습니다.")
    return job


def _require_completed_result(result_id, user_id):
    job = _require_owned_job(result_id, user_id)
    if job["status"] != "COMPLETED":
        raise HTTPException(status_code=409, detail="분석이 아직 완료되지 않았습니다.")
    result = load_analysis_result(result_id)
    if result is None:
        mark_job_result_missing(result_id)
        raise HTTPException(status_code=409, detail="분석 결과 파일이 손상되었거나 존재하지 않습니다.")
    summary = result.get("data", {}).get("summary_result", {})
    file_score = summary.get("total_score")
    db_score = job.get("total_score")
    score_mismatch = (db_score is None) != (file_score is None)
    if db_score is not None and file_score is not None:
        score_mismatch = abs(float(db_score) - float(file_score)) > 0.001
    if score_mismatch:
        sync_job_summary(result_id, summary)
    return result


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
    return {"job": _require_owned_job(result_id, user["id"])}


@router.post("/job/{result_id}/cancel")
def cancel_job(result_id: str, user=Depends(get_current_user)):
    result = request_user_job_cancel(result_id, user["id"])
    if result is None:
        raise HTTPException(status_code=404, detail="분석 작업을 찾을 수 없습니다.")
    if result is False:
        raise HTTPException(status_code=409, detail="대기 또는 처리 중인 작업만 취소할 수 있습니다.")
    return {"message": "cancel requested", "job": _require_owned_job(result_id, user["id"])}


@router.post("/job/{result_id}/retry", status_code=202)
def retry_job(result_id: str, user=Depends(get_current_user)):
    _require_owned_job(result_id, user["id"])
    saved_filename = get_user_job_source_filename(result_id, user["id"])
    if not saved_filename or not (UPLOAD_DIR / saved_filename).exists():
        raise HTTPException(status_code=409, detail="재시도할 원본 영상이 보존되어 있지 않습니다.")
    if not retry_user_job(result_id, user["id"]):
        raise HTTPException(status_code=409, detail="재시도 가능한 실패 또는 취소 작업이 아닙니다.")
    return {"message": "analysis requeued", "job": _require_owned_job(result_id, user["id"])}


@router.get("/result/{result_id}")
def get_result(result_id: str, user=Depends(get_current_user)):
    result = _require_completed_result(result_id, user["id"])
    return {
        "result_id": result["result_id"],
        "created_at": result["created_at"],
        "data": result["data"],
    }


@router.get("/result/{result_id}/summary")
def get_result_summary(result_id: str, user=Depends(get_current_user)):
    result = _require_completed_result(result_id, user["id"])
    summary_result = result.get("data", {}).get("summary_result")
    if summary_result is None:
        raise HTTPException(status_code=404, detail="요약 결과를 찾을 수 없습니다.")
    return {"result_id": result_id, "summary": summary_result}


@router.get("/result/{result_id}/sections")
def get_result_sections(result_id: str, user=Depends(get_current_user)):
    result = _require_completed_result(result_id, user["id"])
    data = result.get("data", {})
    score_result = data.get("score_result", {})
    audio_result = data.get("audio_result", {})
    timeline_result = data.get("timeline_result", {})

    return {
        "result_id": result_id,
        "original_filename": data.get("original_filename"),
        "sections": {
            "summary": data.get("summary_result"),
            "score": score_result,
            "feedback": data.get("feedback_result", {}),
            "speech": {
                "audio_analysis_available": score_result.get("audio_analysis_available"),
                "speech_speed_wpm": audio_result.get("speech_speed_wpm"),
                "speech_speed_score": score_result.get("speech_speed_score"),
                "silence_count": audio_result.get("silence_count"),
                "total_silence_time": audio_result.get("total_silence_time"),
                "silence_score": score_result.get("silence_score"),
            },
            "filler": data.get("filler_result", {}),
            "gesture": data.get("gesture_result", {}),
            "volume": data.get("volume_result", {}),
            "timeline": {
                "duration_seconds": timeline_result.get("duration_seconds"),
                "timeline_count": timeline_result.get("timeline_count"),
            },
        },
    }


@router.get("/result/{result_id}/timeline")
def get_result_timeline(result_id: str, user=Depends(get_current_user)):
    result = _require_completed_result(result_id, user["id"])
    timeline_result = result.get("data", {}).get("timeline_result")
    if timeline_result is None:
        raise HTTPException(status_code=404, detail="타임라인 결과를 찾을 수 없습니다.")
    return {"result_id": result_id, "timeline": timeline_result}


@router.get("/result/{result_id}/timeline/chart")
def get_result_timeline_chart(result_id: str, user=Depends(get_current_user)):
    result = _require_completed_result(result_id, user["id"])
    timeline_result = result.get("data", {}).get("timeline_result")
    if timeline_result is None:
        raise HTTPException(status_code=404, detail="타임라인 결과를 찾을 수 없습니다.")

    chart_data = [
        {
            "time_sec": item.get("time_sec"),
            "frame_score": item.get("frame_score"),
            "pose_score": item.get("pose_score"),
            "shoulder_score": item.get("shoulder_score"),
            "face_score": item.get("face_score"),
            "gaze_score": item.get("gaze_score"),
        }
        for item in timeline_result.get("timeline", [])
    ]
    return {"result_id": result_id, "timeline_count": len(chart_data), "chart_data": chart_data}


@router.get("/results")
def get_results(
    status: str | None = None,
    search: str = Query(default="", max_length=200),
    sort: str = "latest",
    limit: int = Query(default=12, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    cursor: str | None = Query(default=None, max_length=1000),
    user=Depends(get_current_user),
):
    return list_user_jobs(
        user["id"],
        status=status,
        search=search.strip(),
        sort=sort,
        limit=limit,
        offset=offset,
        cursor=cursor,
    )


@router.get("/growth")
def get_growth(user=Depends(get_current_user)):
    return {"growth": list_user_growth(user["id"])}


@router.get("/result/{result_id}/report.md", response_class=PlainTextResponse)
def download_markdown_report(result_id: str, user=Depends(get_current_user)):
    result = _require_completed_result(result_id, user["id"])
    data = result.get("data", {})
    summary = data.get("summary_result", {})
    score = data.get("score_result", {})
    feedback = data.get("feedback_result", {})
    metrics = summary.get("metrics", {})
    lines = [
        f"# 발표 분석 보고서: {data.get('original_filename') or result_id}",
        "",
        f"- 분석 ID: {result_id}",
        f"- 생성일: {result.get('created_at', '-')}",
        f"- 종합 점수: {summary.get('total_score', score.get('total_score', '-'))}",
        f"- 처리 시간: {summary.get('processing_time_seconds', '-')}초",
        "",
        "## 요약 피드백",
        "",
        summary.get("summary_feedback") or feedback.get("summary") or "피드백 없음",
        "",
        "## 핵심 지표",
        "",
    ]
    for key, value in metrics.items():
        lines.append(f"- {key}: {value}")
    details = feedback.get("details", [])
    if details:
        lines.extend(["", "## 개선 제안", ""])
        lines.extend(f"- {item}" for item in details)
    timeline = data.get("timeline_result", {}).get("timeline", [])
    weak_timeline = sorted(timeline, key=lambda item: item.get("frame_score", 100))[:5]
    if weak_timeline:
        lines.extend(["", "## 집중 연습 구간", ""])
        lines.extend(
            f"- {item.get('time_sec', '-')}초: 종합 {item.get('frame_score', '-')}점, "
            f"자세 {item.get('pose_score', '-')}점, 시선 {item.get('gaze_score', '-')}점"
            for item in weak_timeline
        )
    response = PlainTextResponse("\n".join(lines), media_type="text/markdown; charset=utf-8")
    response.headers["Content-Disposition"] = f'attachment; filename="{result_id}.md"'
    return response


@router.delete("/result/{result_id}")
def delete_result(result_id: str, user=Depends(get_current_user)):
    job = _require_owned_job(result_id, user["id"])
    if job["status"] in ("QUEUED", "PROCESSING"):
        raise HTTPException(status_code=409, detail="대기 또는 분석 중인 작업은 먼저 취소해주세요.")

    delete_result_data = delete_analysis_result(result_id)
    practice_removed = delete_practice_context(result_id)
    saved_filename = get_user_job_source_filename(result_id, user["id"])
    result_removed = ensure_file_removed(RESULT_DIR / f"{result_id}.json")
    source_removed = ensure_file_removed(UPLOAD_DIR / saved_filename) if saved_filename else True
    if not result_removed or not source_removed or not practice_removed:
        raise HTTPException(status_code=500, detail="분석 데이터 파일을 완전히 삭제하지 못했습니다. 다시 시도해주세요.")
    deleted_job = delete_user_job(result_id, user["id"])
    return {
        "message": "result deleted",
        "delete_result": delete_result_data or {
            "result_id": result_id,
            "deleted_result_count": 0,
            "total_deleted_count": 0,
        },
        "deleted_job": deleted_job,
    }
