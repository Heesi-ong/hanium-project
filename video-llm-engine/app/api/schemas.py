"""OpenAPI에 노출할 Video LLM 요청/응답 계약."""

from typing import Literal

from pydantic import BaseModel, Field, model_validator


SAFE_JOB_ID_PATTERN = r"^[A-Za-z0-9._-]{1,100}$"


class VideoLlmAnalysisRequest(BaseModel):
    jobId: str = Field(pattern=SAFE_JOB_ID_PATTERN)
    videoPath: str = Field(min_length=1)
    sampleFps: int = Field(default=1, ge=1, le=30)
    maxFrames: int = Field(default=90, ge=1, le=600)
    durationSec: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    videoDownloadUrl: str | None = None
    requireReal: bool = False


class VideoObservation(BaseModel):
    startSec: float = Field(ge=0)
    endSec: float = Field(ge=0)
    label: str = Field(min_length=1)
    description: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def validate_time_range(self):
        if self.endSec < self.startSec:
            raise ValueError("endSec must be greater than or equal to startSec")
        return self


class VideoObservations(BaseModel):
    eyeContact: list[VideoObservation]
    facialExpression: list[VideoObservation]
    gesture: list[VideoObservation]
    posture: list[VideoObservation]


class VideoGlobalSummary(BaseModel):
    visualDelivery: str = Field(min_length=1)
    mainStrength: str = Field(min_length=1)
    mainWeakness: str = Field(min_length=1)


class VideoModelInfo(BaseModel):
    name: str = Field(min_length=1)
    version: str = Field(min_length=1)
    generationMode: Literal["REAL", "MOCK", "FALLBACK"]


class VideoLlmAnalysisResponse(BaseModel):
    jobId: str
    status: Literal["success"]
    model: VideoModelInfo
    observations: VideoObservations
    globalSummary: VideoGlobalSummary

