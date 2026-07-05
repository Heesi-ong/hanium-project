import logging
import os

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from typing import Dict, Any

from app.core.security import verify_internal_api_key

logger = logging.getLogger("video-llm-engine")

router = APIRouter(
    prefix="/api/video-llm",
    tags=["video-llm-analysis"],
    dependencies=[Depends(verify_internal_api_key)],
)


class VideoLlmAnalysisRequest(BaseModel):
    jobId: str
    videoPath: str
    sampleFps: int = 1
    maxFrames: int = 90


def resolve_video_llm_enabled() -> bool:
    return os.getenv("VIDEO_LLM_ENABLED", "false").strip().lower() == "true"


def call_real_video_llm_model(request: VideoLlmAnalysisRequest) -> Dict[str, Any]:
    # 실제 Video LLM 벤더(OpenAI GPT-4o vision / Gemini / 오픈소스 등)가
    # 아직 결정되지 않았습니다. 벤더가 정해지면 이 함수 내부에 실제 API 호출을
    # 구현하고, 반환값은 build_mock_response와 동일한 스키마
    # (jobId/status/model/observations/globalSummary)를 따라야 합니다.
    raise NotImplementedError("실제 Video LLM 모델 호출이 아직 구현되지 않았습니다.")


def build_mock_response(request: VideoLlmAnalysisRequest, generation_mode: str) -> Dict[str, Any]:
    return {
        "jobId": request.jobId,
        "status": "success",
        "model": {
            "name": "mock-video-llm",
            "version": "local-mock",
            "generationMode": generation_mode,
        },
        "observations": {
            "eyeContact": [
                {
                    "startSec": 12,
                    "endSec": 18,
                    "label": "looking_down",
                    "description": "중간 구간에서 시선이 아래로 이동하는 장면이 관찰되었습니다.",
                    "confidence": 0.74
                }
            ],
            "facialExpression": [
                {
                    "startSec": 20,
                    "endSec": 35,
                    "label": "low_variation",
                    "description": "표정 변화가 적고 다소 경직되어 보입니다.",
                    "confidence": 0.68
                }
            ],
            "gesture": [
                {
                    "startSec": 5,
                    "endSec": 45,
                    "label": "low",
                    "description": "손동작 사용이 적어 강조 표현이 약하게 보입니다.",
                    "confidence": 0.71
                }
            ],
            "posture": [
                {
                    "startSec": 0,
                    "endSec": 60,
                    "label": "stable",
                    "description": "상체 자세는 전반적으로 안정적입니다.",
                    "confidence": 0.81
                }
            ]
        },
        "globalSummary": {
            "visualDelivery": "발표자는 전반적으로 안정적이지만 시선과 제스처에서 개선 여지가 있습니다.",
            "mainStrength": "상체 자세가 비교적 안정적입니다.",
            "mainWeakness": "시선이 아래로 이동하는 구간과 제스처 부족이 관찰됩니다."
        }
    }


@router.post("/analyze")
def analyze_video(request: VideoLlmAnalysisRequest) -> Dict[str, Any]:
    if resolve_video_llm_enabled():
        try:
            return call_real_video_llm_model(request)
        except Exception:
            logger.exception(
                "(%s) 실제 Video LLM 호출 실패, mock 응답으로 폴백합니다.",
                request.jobId,
            )
            return build_mock_response(request, "FALLBACK")

    logger.info("(%s) Mock 영상 관찰 결과를 생성하는 중...", request.jobId)
    return build_mock_response(request, "MOCK")
