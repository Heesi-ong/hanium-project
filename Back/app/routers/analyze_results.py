"""분석 결과 목록, 상세, 요약, 섹션, 타임라인, 성장 추이 조회 API를 담당한다."""

from fastapi import APIRouter, Depends, HTTPException, Query

from ..services.analysis_jobs import list_user_growth, list_user_jobs
from ..services.auth_service import get_current_user
from .analyze_common import require_completed_result

router = APIRouter(prefix="/analyze", tags=["Analyze"])


@router.get("/result/{result_id}")
def get_result(result_id: str, user=Depends(get_current_user)):
    result = require_completed_result(result_id, user["id"])
    return {
        "result_id": result["result_id"],
        "created_at": result["created_at"],
        "data": result["data"],
    }


@router.get("/result/{result_id}/summary")
def get_result_summary(result_id: str, user=Depends(get_current_user)):
    result = require_completed_result(result_id, user["id"])
    summary_result = result.get("data", {}).get("summary_result")
    if summary_result is None:
        raise HTTPException(status_code=404, detail="요약 결과를 찾을 수 없습니다.")
    return {"result_id": result_id, "summary": summary_result}


@router.get("/result/{result_id}/sections")
def get_result_sections(result_id: str, user=Depends(get_current_user)):
    result = require_completed_result(result_id, user["id"])
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
                "speech_speed_spm": audio_result.get("speech_speed_spm"),
                "speech_speed_basis": audio_result.get("speech_speed_basis"),
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
    result = require_completed_result(result_id, user["id"])
    timeline_result = result.get("data", {}).get("timeline_result")
    if timeline_result is None:
        raise HTTPException(status_code=404, detail="타임라인 결과를 찾을 수 없습니다.")
    return {"result_id": result_id, "timeline": timeline_result}


@router.get("/result/{result_id}/timeline/chart")
def get_result_timeline_chart(result_id: str, user=Depends(get_current_user)):
    result = require_completed_result(result_id, user["id"])
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
            "head_direction_score": item.get("head_direction_score"),
            "yaw_degrees": item.get("yaw_degrees"),
            "pitch_degrees": item.get("pitch_degrees"),
            "roll_degrees": item.get("roll_degrees"),
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
