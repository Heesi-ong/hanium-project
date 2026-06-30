from fastapi import APIRouter
from pydantic import BaseModel
from typing import Dict, Any

router = APIRouter(prefix="/api/video-llm", tags=["video-llm-analysis"])


class VideoLlmAnalysisRequest(BaseModel):
    jobId: str
    videoPath: str
    sampleFps: int = 1
    maxFrames: int = 90


@router.post("/analyze")
def analyze_video(request: VideoLlmAnalysisRequest) -> Dict[str, Any]:
    return {
        "jobId": request.jobId,
        "status": "success",
        "model": {
            "name": "mock-video-llm",
            "version": "local-mock"
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