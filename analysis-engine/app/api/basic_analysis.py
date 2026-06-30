from fastapi import APIRouter
from pydantic import BaseModel
from typing import Dict, Any

router = APIRouter(prefix="/api", tags=["basic-analysis"])


class BasicAnalysisRequest(BaseModel):
    jobId: str
    videoPath: str


@router.post("/basic-analysis")
def basic_analysis(request: BasicAnalysisRequest) -> Dict[str, Any]:
    return {
        "jobId": request.jobId,
        "status": "success",
        "videoInfo": {
            "videoPath": request.videoPath,
            "durationSec": 60,
            "fps": 30,
            "width": 1920,
            "height": 1080
        },
        "audio": {
            "speechSpeedWpm": 132,
            "silenceCount": 3,
            "totalSilenceTime": 5.2
        },
        "filler": {
            "fillerCount": 4,
            "fillerRatio": 0.03
        },
        "pose": {
            "detectionRate": 0.92,
            "postureScore": 82,
            "shoulderBalanceScore": 85
        },
        "face": {
            "detectionRate": 0.88,
            "gazeScore": 74,
            "eyeContactLevel": "normal"
        },
        "score": {
            "totalScore": 76,
            "postureScore": 82,
            "gazeScore": 74,
            "speechScore": 72
        }
    }