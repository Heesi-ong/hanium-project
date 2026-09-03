"""OpenAPI에 노출할 정량 분석 요청/응답 계약."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


JOB_ID_PATTERN = r"^\d{14}-[0-9a-f]{8}$"


class BasicAnalysisRequest(BaseModel):
    jobId: str = Field(pattern=JOB_ID_PATTERN)
    videoPath: str
    videoDownloadUrl: str | None = None


class VideoInfoResponse(BaseModel):
    videoPath: str
    durationSec: float = Field(ge=0)
    fps: float = Field(ge=0)
    frameCount: int = Field(ge=0)
    width: int = Field(ge=0)
    height: int = Field(ge=0)
    fileSize: int = Field(ge=0)


class FrameAnalysisResponse(BaseModel):
    savedCount: int = Field(ge=0)
    frameDirectory: str
    sampledFrames: list[dict[str, Any]]


class ScoreResponse(BaseModel):
    model_config = ConfigDict(extra="allow")

    totalScore: float = Field(ge=0, le=100)
    postureScore: float = Field(ge=0, le=100)
    gazeScore: float = Field(ge=0, le=100)
    speechScore: float = Field(ge=0, le=100)
    gestureScore: float = Field(ge=0, le=100)
    expressionScore: float = Field(ge=0, le=100)


class AnalysisErrorResponse(BaseModel):
    reason: str


class BasicAnalysisResponse(BaseModel):
    """엔진 내부 세부 분석은 확장 가능하게 두고 안정된 top-level을 고정한다."""

    jobId: str
    status: Literal["success", "failed"]
    # 사용자에게 "이 영상이 이렇게 분석됐다"를 보여주기 위한 단계별 처리 로그입니다.
    # 분석 정확도와 무관한 부가 정보라 선택 필드로 둡니다.
    analysisTrace: list[dict[str, Any]] = Field(default_factory=list)
    # 샘플 프레임 위에 포즈/제스처 분석 결과를 그린 base64 JPEG 목록입니다.
    # 백엔드가 스토리지에 저장한 뒤 최종 결과 응답에서는 경로 참조로 대체합니다.
    frameOverlays: list[dict[str, Any]] = Field(default_factory=list)
    videoInfo: VideoInfoResponse
    frame: FrameAnalysisResponse
    audio: dict[str, Any]
    filler: dict[str, Any]
    pose: dict[str, Any]
    gesture: dict[str, Any]
    face: dict[str, Any]
    emotion: dict[str, Any]
    score: ScoreResponse
    error: AnalysisErrorResponse | None = None

