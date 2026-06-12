from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..services.analysis_jobs import get_user_job, list_user_growth
from ..services.auth_service import get_current_user
from ..services.practice_coaching import (
    PURPOSES,
    build_practice_coaching,
    enrich_growth,
    find_previous_same_series,
    list_practice_series,
    load_practice_context,
    save_practice_context,
)
from ..services.result_saver import load_analysis_result

router = APIRouter(prefix="/analyze/practice", tags=["Practice"])


class PracticeContextRequest(BaseModel):
    purpose: str = Field(pattern="^(class|project|interview|business|sales)$")
    audience: str = Field(default="", max_length=120)
    target_minutes: int | None = Field(default=None, ge=1, le=180)
    core_message: str = Field(default="", max_length=500)
    series_name: str = Field(default="", max_length=120)
    series_id: str | None = Field(default=None, max_length=64)


def _owned_job(result_id, user_id):
    job = get_user_job(result_id, user_id)
    if not job:
        raise HTTPException(status_code=404, detail="분석 작업을 찾을 수 없습니다.")
    return job


@router.get("/purposes")
def get_purposes(_user=Depends(get_current_user)):
    return {"purposes": [{"key": key, **value} for key, value in PURPOSES.items()]}


@router.get("/series")
def get_series(user=Depends(get_current_user)):
    return {"series": list_practice_series(user["id"])}


@router.put("/{result_id}")
def update_practice_context(result_id: str, request: PracticeContextRequest, user=Depends(get_current_user)):
    _owned_job(result_id, user["id"])
    context_data = request.model_dump()
    if request.series_id:
        series = next(
            (item for item in list_practice_series(user["id"]) if item.get("series_id") == request.series_id),
            None,
        )
        if not series or series["purpose"] != request.purpose:
            raise HTTPException(status_code=400, detail="선택한 연습 시리즈를 사용할 수 없습니다.")
        context_data["series_name"] = series["series_name"]
        context_data["series_id_source"] = "legacy_selected" if series.get("legacy") else "selected"
    context = save_practice_context(result_id, user["id"], context_data)
    return {"practice_context": context}


@router.get("/growth/all")
def get_practice_growth(user=Depends(get_current_user)):
    return {"growth": enrich_growth(list_user_growth(user["id"]), user["id"])}


@router.get("/{result_id}")
def get_practice_coaching(result_id: str, user=Depends(get_current_user)):
    job = _owned_job(result_id, user["id"])
    if job["status"] != "COMPLETED":
        raise HTTPException(status_code=409, detail="분석 완료 후 연습 계획을 확인할 수 있습니다.")
    result = load_analysis_result(result_id)
    if not result:
        raise HTTPException(status_code=404, detail="분석 결과를 찾을 수 없습니다.")
    context = load_practice_context(result_id, user["id"]) or {
        "purpose": "project",
        "audience": "",
        "target_minutes": PURPOSES["project"]["recommended_minutes"],
        "core_message": "",
        "series_name": "",
    }
    growth = list_user_growth(user["id"])
    previous = find_previous_same_series(growth, user["id"], result_id, context)
    return {"coaching": build_practice_coaching(result, context, previous)}
