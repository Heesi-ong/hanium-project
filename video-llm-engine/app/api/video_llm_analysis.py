import logging
import math
import time
from typing import Any, Dict

from fastapi import APIRouter, Depends, Header, HTTPException
from app.core.logging_config import bind_job_id, bind_request_id
from app.core.security import verify_internal_api_key
from app.api.schemas import VideoLlmAnalysisRequest, VideoLlmAnalysisResponse
from app.services import (
    media_io,
    nvidia_prompt,
    nvidia_response,
    nvidia_runtime,
    video_pipeline,
)
from app.core.settings import (
    NVIDIA_DEFAULT_MODEL as NVIDIA_DEFAULT_MODEL,
    get_settings,
)

logger = logging.getLogger("video-llm-engine")

router = APIRouter(
    prefix="/api/video-llm",
    tags=["video-llm-analysis"],
    dependencies=[Depends(verify_internal_api_key)],
)


def resolve_video_llm_enabled() -> bool:
    return get_settings().enabled


# 이 이미지가 어떤 의존성 세트로 빌드되었는지(Dockerfile의 VIDEO_LLM_BACKEND build arg와
# 짝을 이룹니다). mock/external-api는 requirements-base.txt만으로 충분하고,
# local-model만 무거운 torch/transformers 스택(requirements-real-model.txt)이 필요합니다.
# 런타임 동작 자체는 여전히 VIDEO_LLM_ENABLED(위)로 켜고 끕니다 — 이 값은 "실제로 어떤
# 방식의 구현을 기대할 수 있는 이미지인지"를 알려주는 관측용 정보입니다.
def resolve_video_llm_backend() -> str:
    return get_settings().installed_backend


# 기존 애플리케이션 초기화 경로와 내부 호출자를 위한 호환 alias입니다.
configure_runtime = nvidia_runtime.configure_runtime
resolve_real_model_semaphore_timeout_seconds = (
    nvidia_runtime.resolve_semaphore_timeout_seconds
)


# 라이브 테스트에서 성공이 확인된 안전 구간(120초)보다 여유를 둔 기본값입니다. 이보다
# 긴 영상은 이 길이 단위로 실제로 잘라(ffmpeg) 구간마다 독립적으로 NVIDIA를 호출합니다.
# 프롬프트 지시만으로 긴 타임라인을 구간화하게 하는 것보다, 모델이 실제로 그 구간만
# 보고 답하게 하는 쪽이 더 정직한 시간 구간화 품질을 기대할 수 있습니다
# (docs/service-plan/video-llm-model-options.md의 3구간 프롬프트 한계 참고).
def resolve_video_llm_chunk_duration_seconds() -> float:
    return get_settings().chunk_duration_seconds


def resolve_video_llm_segment_split_timeout_seconds() -> float:
    return get_settings().segment_split_timeout_seconds


# backend가 업로드 시점에 이미 VIDEO_MAX_DURATION_MINUTES(기본 30분)로 영상 길이를
# 제한하지만, 이 엔진은 그 값을 그대로 신뢰해 durationSec만큼 ffmpeg 세그먼트 분할
# 루프를 돈다. durationSec에 단위 착오(예: ms를 초로 전달) 같은 잘못된 값이 들어오면
# 세그먼트 수가 비정상적으로 커져 sync 스레드풀을 고갈시킬 수 있어, 방어적으로 한 번
# 더 상한을 둔다. 업로드 정책보다 넉넉하게 잡아 정상적인 긴 영상은 막지 않는다.
def resolve_video_llm_max_duration_seconds() -> float:
    return get_settings().max_duration_seconds


def validate_duration_sec(duration_sec: float | None) -> None:
    if duration_sec is None:
        return

    if not math.isfinite(duration_sec) or duration_sec <= 0:
        raise RuntimeError(
            f"durationSec must be a positive finite number, got {duration_sec!r}."
        )

    max_duration_seconds = resolve_video_llm_max_duration_seconds()
    if duration_sec > max_duration_seconds:
        raise RuntimeError(
            f"durationSec={duration_sec} exceeds VIDEO_LLM_MAX_DURATION_SECONDS={max_duration_seconds}."
        )


def resolve_nvidia_timeout_seconds() -> float:
    return get_settings().nvidia_timeout_seconds


def resolve_video_llm_total_timeout_seconds() -> float:
    return get_settings().total_timeout_seconds


def call_real_video_llm_model(request: VideoLlmAnalysisRequest) -> Dict[str, Any]:
    settings = get_settings()
    api_key = settings.nvidia_api_key
    if not api_key:
        raise RuntimeError("NVIDIA_API_KEY is required when VIDEO_LLM_ENABLED=true.")

    model = settings.nvidia_model
    base_url = settings.nvidia_api_base_url
    asset_base_url = settings.nvidia_asset_api_base_url
    timeout_seconds = settings.nvidia_timeout_seconds
    chunk_duration_seconds = settings.chunk_duration_seconds
    deadline_monotonic = time.monotonic() + resolve_video_llm_total_timeout_seconds()

    validate_duration_sec(request.durationSec)

    if request.durationSec is not None and request.durationSec > chunk_duration_seconds:
        return call_real_video_llm_model_in_chunks(
            request,
            api_key,
            model,
            base_url,
            asset_base_url,
            timeout_seconds,
            chunk_duration_seconds,
            deadline_monotonic,
        )

    with media_io.resolve_video_file(request, deadline_monotonic) as (
        video_path,
        content_type,
    ):
        model_json = call_nvidia_chat_completion(
            api_key=api_key,
            model=model,
            base_url=base_url,
            asset_base_url=asset_base_url,
            timeout_seconds=timeout_seconds,
            job_id=request.jobId,
            video_path=video_path,
            content_type=content_type,
            duration_hint_sec=request.durationSec,
            sample_fps=request.sampleFps,
            max_frames=request.maxFrames,
            deadline_monotonic=deadline_monotonic,
        )

    return nvidia_response.normalize_video_llm_response(
        request.jobId, model, model_json, request.durationSec
    )


def call_real_video_llm_model_in_chunks(
    request: VideoLlmAnalysisRequest,
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    chunk_duration_seconds: float,
    deadline_monotonic: float | None = None,
) -> Dict[str, Any]:
    return video_pipeline.analyze_video_in_chunks(
        request=request,
        api_key=api_key,
        model=model,
        base_url=base_url,
        asset_base_url=asset_base_url,
        timeout_seconds=timeout_seconds,
        chunk_duration_seconds=chunk_duration_seconds,
        deadline_monotonic=deadline_monotonic,
        call_chat_completion=call_nvidia_chat_completion,
        split_timeout_seconds=resolve_video_llm_segment_split_timeout_seconds(),
    )


# 기존 내부 import 경로를 사용하는 테스트/도구를 위한 호환 alias입니다.
split_video_into_segments = video_pipeline.split_video_into_segments


def call_nvidia_chat_completion(
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    job_id: str,
    video_path,
    content_type: str,
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
    deadline_monotonic: float | None = None,
) -> Dict[str, Any]:
    return nvidia_runtime.call_chat_completion(
        api_key=api_key,
        model=model,
        base_url=base_url,
        asset_base_url=asset_base_url,
        timeout_seconds=timeout_seconds,
        job_id=job_id,
        video_path=video_path,
        content_type=content_type,
        duration_hint_sec=duration_hint_sec,
        sample_fps=sample_fps,
        max_frames=max_frames,
        deadline_monotonic=deadline_monotonic,
    )


# 기존 내부 import 경로를 사용하는 테스트/도구와의 호환성을 유지합니다. 실제 구현 책임은
# app.services.nvidia_prompt에 있습니다.
build_nvidia_chat_completion_payload = nvidia_prompt.build_nvidia_chat_completion_payload
build_duration_prompt = nvidia_prompt.build_duration_prompt


def build_mock_response(
    request: VideoLlmAnalysisRequest, generation_mode: str
) -> Dict[str, Any]:
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
                    "confidence": 0.74,
                }
            ],
            "facialExpression": [
                {
                    "startSec": 20,
                    "endSec": 35,
                    "label": "low_variation",
                    "description": "표정 변화가 적고 다소 경직되어 보입니다.",
                    "confidence": 0.68,
                }
            ],
            "gesture": [
                {
                    "startSec": 5,
                    "endSec": 45,
                    "label": "low",
                    "description": "손동작 사용이 적어 강조 표현이 약하게 보입니다.",
                    "confidence": 0.71,
                }
            ],
            "posture": [
                {
                    "startSec": 0,
                    "endSec": 60,
                    "label": "stable",
                    "description": "상체 자세는 전반적으로 안정적입니다.",
                    "confidence": 0.81,
                }
            ],
        },
        "globalSummary": {
            "visualDelivery": "발표자는 전반적으로 안정적이지만 시선과 제스처에서 개선 여지가 있습니다.",
            "mainStrength": "상체 자세가 비교적 안정적입니다.",
            "mainWeakness": "시선이 아래로 이동하는 구간과 제스처 부족이 관찰됩니다.",
        },
    }


@router.post("/analyze", response_model=VideoLlmAnalysisResponse)
def analyze_video(
    request: VideoLlmAnalysisRequest,
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> Dict[str, Any]:
    with bind_job_id(request.jobId), bind_request_id(x_request_id):
        settings = get_settings()
        if settings.enabled:
            try:
                return call_real_video_llm_model(request)
            except Exception as exception:
                if settings.policy == "STRICT" or request.requireReal:
                    logger.exception(
                        "(%s) 실제 Video LLM 호출 실패, fallback 금지 요청으로 실패 처리합니다. "
                        "policy=%s requireReal=%s",
                        request.jobId,
                        settings.policy,
                        request.requireReal,
                    )
                    raise HTTPException(
                        status_code=502,
                        detail={
                            "code": "VIDEO_LLM_REAL_MODEL_FAILED",
                            "message": "실제 Video LLM 분석에 실패했습니다.",
                        },
                    ) from exception
                logger.exception(
                    "(%s) 실제 Video LLM 호출 실패, DEGRADED 정책에 따라 mock 응답으로 폴백합니다.",
                    request.jobId,
                )
                return build_mock_response(request, "FALLBACK")

        if request.requireReal:
            logger.warning(
                "(%s) requireReal 요청이지만 Video LLM 정책이 DISABLED라 요청을 거부합니다.",
                request.jobId,
            )
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "VIDEO_LLM_REAL_MODEL_DISABLED",
                    "message": "현재 실제 Video LLM 분석을 사용할 수 없습니다.",
                },
            )

        logger.info("(%s) Mock 영상 관찰 결과를 생성하는 중...", request.jobId)
        return build_mock_response(request, "MOCK")
