import base64
from contextlib import contextmanager
import json
import logging
import math
import mimetypes
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
from typing import Any, Dict, Iterator
from urllib.parse import urlparse

import httpx
import imageio_ffmpeg
from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel

from app.core.logging_config import bind_job_id, bind_request_id
from app.core.security import verify_internal_api_key
from app.core.settings import (
    NVIDIA_DEFAULT_MODEL as NVIDIA_DEFAULT_MODEL,
    VideoLlmSettings,
    get_settings,
)

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
    durationSec: float | None = None
    videoDownloadUrl: str | None = None
    requireReal: bool = False


def resolve_video_llm_enabled() -> bool:
    return get_settings().enabled


# 이 이미지가 어떤 의존성 세트로 빌드되었는지(Dockerfile의 VIDEO_LLM_BACKEND build arg와
# 짝을 이룹니다). mock/external-api는 requirements-base.txt만으로 충분하고,
# local-model만 무거운 torch/transformers 스택(requirements-real-model.txt)이 필요합니다.
# 런타임 동작 자체는 여전히 VIDEO_LLM_ENABLED(위)로 켜고 끕니다 — 이 값은 "실제로 어떤
# 방식의 구현을 기대할 수 있는 이미지인지"를 알려주는 관측용 정보입니다.
def resolve_video_llm_backend() -> str:
    return get_settings().installed_backend


NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES = 180 * 1024
VIDEO_STREAM_CHUNK_SIZE_BYTES = 1024 * 1024
NVIDIA_STATUS_POLL_MAX_ATTEMPTS = 10
NVIDIA_STATUS_POLL_INTERVAL_SECONDS = 1

# NVIDIA hosted API의 공식 SLA 문서는 없지만, 계정당 약 40 RPM을 공유한다는 보고가
# 있습니다(docs/service-plan/video-llm-model-options.md 참고). 여러 분석 작업이 동시에
# 겹치면 이 순간 대역에 몰려 429가 날 수 있어, 앱 레벨에서 실제 모델 호출의 동시
# 실행 수 자체를 낮게 제한합니다. 이 라우트는 sync def라 FastAPI가 스레드풀에서
# 실행하므로 threading.Semaphore를 씁니다. 세마포어의 초기 permit 수는 객체 생성
# 시점에 고정되는 값이라(다른 resolve_* 함수들과 달리) 프로세스 기동 시 한 번만
# 읽습니다. 대기 timeout은 호출마다 다시 읽을 수 있어 resolve 함수로 분리했습니다.
_REAL_MODEL_SEMAPHORE = threading.Semaphore(3)


def configure_runtime(settings: VideoLlmSettings) -> None:
    global _REAL_MODEL_SEMAPHORE
    _REAL_MODEL_SEMAPHORE = threading.Semaphore(settings.real_model_max_concurrency)


def resolve_real_model_semaphore_timeout_seconds() -> float:
    return get_settings().real_model_semaphore_timeout_seconds


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
        raise RuntimeError(f"durationSec must be a positive finite number, got {duration_sec!r}.")

    max_duration_seconds = resolve_video_llm_max_duration_seconds()
    if duration_sec > max_duration_seconds:
        raise RuntimeError(
            f"durationSec={duration_sec} exceeds VIDEO_LLM_MAX_DURATION_SECONDS={max_duration_seconds}."
        )


OBSERVATION_CATEGORIES = (
    "eyeContact",
    "facialExpression",
    "gesture",
    "posture",
)
SUMMARY_FIELDS = (
    "visualDelivery",
    "mainStrength",
    "mainWeakness",
)


def resolve_video_max_size_bytes() -> int:
    return get_settings().max_video_size_bytes


def resolve_nvidia_timeout_seconds() -> float:
    return get_settings().nvidia_timeout_seconds
def resolve_allowed_video_base_dir() -> Path:
    return get_settings().allowed_video_base_dir


# request.videoPath는 backend가 만들어 보내는 값이라 지금은 항상 안전한 경로만 들어오지만,
# analysis-engine의 job_id 검증(경로 이탈 방지)과 같은 이유로 이 엔진도 방어적으로 한 번 더
# 검증합니다. 이 경로의 내용은 그대로 읽혀 제3자(NVIDIA)로 업로드되므로, backend가 버그나
# 침해로 잘못된 경로를 보내는 경우 임의 파일이 외부로 유출되는 것을 막습니다.
def validate_local_video_path(video_path: Path) -> None:
    allowed_base_dir = resolve_allowed_video_base_dir()
    resolved_path = video_path.resolve()

    if resolved_path != allowed_base_dir and allowed_base_dir not in resolved_path.parents:
        raise ValueError(
            f"videoPath must be inside {allowed_base_dir}, got {video_path!r} "
            f"(resolved to {resolved_path})."
        )


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
        )

    with resolve_video_file(request) as (video_path, content_type):
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
        )

    return normalize_video_llm_response(request.jobId, model, model_json, request.durationSec)


def call_real_video_llm_model_in_chunks(
    request: VideoLlmAnalysisRequest,
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    chunk_duration_seconds: float,
) -> Dict[str, Any]:
    """긴 영상을 chunk_duration_seconds 단위로 실제로 잘라(ffmpeg) NVIDIA를 구간마다
    호출하고 결과를 하나로 합칩니다. 세그먼트 하나라도 생성 또는 분석에 실패하면 전체
    호출을 실패시킵니다. 누락 구간이 있는 부분 결과를 REAL로 표시하면 STRICT/requireReal
    계약을 위반하므로, 상위 analyze_video()가 정책에 따라 502 또는 전체 FALLBACK으로
    처리하게 합니다.
    """
    total_segment_count = math.ceil(request.durationSec / chunk_duration_seconds)

    with resolve_video_file(request) as (video_path, content_type):
        with split_video_into_segments(
            video_path, chunk_duration_seconds, request.durationSec
        ) as segment_paths:
            merged_observations: Dict[str, list] = {
                category: [] for category in OBSERVATION_CATEGORIES
            }
            summary_parts: Dict[str, list] = {field: [] for field in SUMMARY_FIELDS}

            if len(segment_paths) != total_segment_count:
                raise RuntimeError(
                    "Video LLM 세그먼트 생성 결과가 예상 개수와 다릅니다. "
                    f"expected={total_segment_count}, actual={len(segment_paths)}"
                )

            # segment_paths는 (원래 청크 인덱스, 파일 경로) 튜플 목록입니다. 원래 인덱스로
            # 시간 오프셋을 계산해 startSec/endSec이 정확한 전체 영상 시각을 유지합니다.
            for original_index, segment_path in segment_paths:
                segment_start_offset = original_index * chunk_duration_seconds
                segment_local_duration = min(
                    chunk_duration_seconds, request.durationSec - segment_start_offset
                )

                segment_model_json = call_nvidia_chat_completion(
                    api_key=api_key,
                    model=model,
                    base_url=base_url,
                    asset_base_url=asset_base_url,
                    timeout_seconds=timeout_seconds,
                    job_id=f"{request.jobId}-segment-{original_index}",
                    video_path=segment_path,
                    content_type=content_type,
                    duration_hint_sec=segment_local_duration,
                    sample_fps=request.sampleFps,
                    max_frames=request.maxFrames,
                )
                segment_normalized = normalize_video_llm_response(
                    request.jobId, model, segment_model_json, segment_local_duration
                )

                for category in OBSERVATION_CATEGORIES:
                    for item in segment_normalized["observations"][category]:
                        offset_item = dict(item)
                        offset_item["startSec"] = round(item["startSec"] + segment_start_offset, 3)
                        offset_item["endSec"] = round(item["endSec"] + segment_start_offset, 3)
                        merged_observations[category].append(offset_item)

                segment_end_offset = segment_start_offset + segment_local_duration
                for field in SUMMARY_FIELDS:
                    summary_parts[field].append(
                        f"[{segment_start_offset:.0f}-{segment_end_offset:.0f}s] "
                        f"{segment_normalized['globalSummary'][field]}"
                    )

    merged_model_json = {
        "observations": merged_observations,
        "globalSummary": {
            field: " ".join(parts) for field, parts in summary_parts.items()
        },
    }

    # 세그먼트별로는 각자의 로컬 구간 길이로 이미 클램프했지만, 반올림/마지막 구간 오차에
    # 대비해 전체 영상 길이 기준으로 한 번 더 검증/클램프합니다.
    return normalize_video_llm_response(
        request.jobId, model, merged_model_json, request.durationSec
    )


@contextmanager
def split_video_into_segments(
    video_path: Path,
    chunk_duration_seconds: float,
    total_duration_sec: float,
) -> Iterator[list[tuple[int, Path]]]:
    """ffmpeg -c copy로 원본을 재인코딩 없이 chunk_duration_seconds 단위로 잘라, 생성된
    세그먼트의 (원래 청크 인덱스, 파일 경로) 목록을 만듭니다. 임시 디렉터리는 이 컨텍스트를
    벗어나면 정리됩니다. 인덱스를 함께 반환하는 이유는, 일부 세그먼트가 생성에 실패해
    목록에서 빠지더라도 호출부가 시간 오프셋을 원래 위치 기준으로 정확히 계산할 수 있게
    하기 위해서입니다(단순 목록 위치로 계산하면 실패 이후 모든 구간의 시간이 밀립니다).
    """
    segment_count = math.ceil(total_duration_sec / chunk_duration_seconds)
    output_dir = Path(tempfile.mkdtemp(prefix="video-llm-segments-"))
    ffmpeg_executable = imageio_ffmpeg.get_ffmpeg_exe()
    split_timeout_seconds = resolve_video_llm_segment_split_timeout_seconds()
    suffix = video_path.suffix or ".mp4"

    try:
        segment_paths: list[tuple[int, Path]] = []

        for index in range(segment_count):
            start_sec = index * chunk_duration_seconds
            output_path = output_dir / f"segment-{index}{suffix}"

            subprocess.run(
                [
                    ffmpeg_executable,
                    "-y",
                    "-ss", str(start_sec),
                    "-t", str(chunk_duration_seconds),
                    "-i", str(video_path),
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    str(output_path),
                ],
                check=True,
                capture_output=True,
                timeout=split_timeout_seconds,
            )

            if output_path.exists() and output_path.stat().st_size > 0:
                segment_paths.append((index, output_path))
            else:
                raise RuntimeError(
                    "ffmpeg가 세그먼트를 만들지 못했습니다(빈 출력). "
                    f"segment={index + 1}/{segment_count}"
                )

        if not segment_paths:
            raise RuntimeError("ffmpeg가 영상을 세그먼트로 나누지 못했습니다(생성된 세그먼트 없음).")

        yield segment_paths
    finally:
        shutil.rmtree(output_dir, ignore_errors=True)


def call_nvidia_chat_completion(
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    job_id: str,
    video_path: Path,
    content_type: str,
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
) -> Dict[str, Any]:
    """세마포어로 보호된 단일 NVIDIA chat/completions 호출입니다. video_path가 가리키는
    파일(전체 영상 또는 분할된 세그먼트) 하나를 보내고, 정규화 이전의 원본 model_json을
    반환합니다 — 여러 세그먼트를 하나로 합치는 상위 호출부가 정규화/시간 오프셋을 책임집니다.
    """
    url = f"{base_url}/chat/completions"

    # NVIDIA 계정 전체가 공유하는 순간 rate limit(약 40 RPM으로 알려짐)에 여러 분석
    # 작업(과 한 작업 안의 여러 세그먼트)이 동시에 부딪히지 않도록, 실제 모델 호출 자체를
    # 세마포어로 감쌉니다. 순번을 제한 시간 안에 확보하지 못하면 예외를 던져, 호출부의
    # 기존 FALLBACK 폴백 경로를 그대로 타게 합니다.
    semaphore_timeout_seconds = resolve_real_model_semaphore_timeout_seconds()
    acquired = _REAL_MODEL_SEMAPHORE.acquire(timeout=semaphore_timeout_seconds)
    if not acquired:
        raise RuntimeError(
            "Video LLM 실제 모델 동시 호출 제한에 걸려 "
            f"{semaphore_timeout_seconds}초 안에 순번을 확보하지 못했습니다."
        )

    try:
        started_at = time.monotonic()
        response_status = "not_sent"
        asset_id = None

        try:
            with httpx.Client(timeout=timeout_seconds) as client:
                video_input = build_nvidia_video_input_from_local_file(
                    client=client,
                    api_key=api_key,
                    asset_base_url=asset_base_url,
                    video_path=video_path,
                    content_type=content_type,
                    description=f"video-llm-analysis jobId={job_id}",
                )
                asset_id = video_input.get("asset_id")
                payload = build_nvidia_chat_completion_payload(
                    duration_hint_sec, sample_fps, max_frames, model, video_input
                )
                headers = {
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                }
                if asset_id:
                    headers["NVCF-INPUT-ASSET-REFERENCES"] = asset_id

                response = client.post(
                    url,
                    headers=headers,
                    json=payload,
                )
                response_status = str(response.status_code)
                if response.status_code == 202:
                    response = poll_nvidia_chat_completion_result(
                        client=client,
                        base_url=base_url,
                        api_key=api_key,
                        initial_response=response,
                    )
                    response_status = f"202->{response.status_code}"
                else:
                    response.raise_for_status()
        finally:
            if asset_id:
                with httpx.Client(timeout=timeout_seconds) as cleanup_client:
                    delete_nvidia_asset(
                        cleanup_client,
                        api_key,
                        asset_base_url,
                        asset_id,
                    )
            elapsed_ms = int((time.monotonic() - started_at) * 1000)
            logger.info(
                "NVIDIA_VIDEO_LLM_USAGE jobId=%s model=%s generationMode=REAL status=%s elapsedMs=%s",
                job_id,
                model,
                response_status,
                elapsed_ms,
            )

        content = extract_chat_completion_content(response.json())
        return parse_model_json(content)
    finally:
        _REAL_MODEL_SEMAPHORE.release()


def build_nvidia_chat_completion_payload(
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
    model: str,
    video_input: Dict[str, str | None],
) -> Dict[str, Any]:
    system_prompt = (
        "/no_think\n"
        "You are a presentation-coaching video analyst. Return only strict JSON. "
        "Do not wrap the JSON in Markdown. The JSON must match the requested schema exactly."
    )
    duration_prompt = build_duration_prompt(duration_hint_sec)
    user_prompt = (
        "Analyze the uploaded presentation video for visible delivery behavior. "
        "Return JSON with this exact shape: "
        "{"
        "\"observations\":{"
        "\"eyeContact\":[{\"startSec\":0,\"endSec\":0,\"label\":\"string\","
        "\"description\":\"string\",\"confidence\":0.0}],"
        "\"facialExpression\":[{\"startSec\":0,\"endSec\":0,\"label\":\"string\","
        "\"description\":\"string\",\"confidence\":0.0}],"
        "\"gesture\":[{\"startSec\":0,\"endSec\":0,\"label\":\"string\","
        "\"description\":\"string\",\"confidence\":0.0}],"
        "\"posture\":[{\"startSec\":0,\"endSec\":0,\"label\":\"string\","
        "\"description\":\"string\",\"confidence\":0.0}]"
        "},"
        "\"globalSummary\":{"
        "\"visualDelivery\":\"string\","
        "\"mainStrength\":\"string\","
        "\"mainWeakness\":\"string\""
        "}"
        "}. "
        "Use seconds from the start of the video. Keep confidence between 0 and 1. "
        f"{duration_prompt}"
        f"Sampling hint from caller: sampleFps={sample_fps}, maxFrames={max_frames}."
    )

    if video_input.get("asset_id"):
        return {
            "model": model,
            "messages": [
                {
                    "role": "user",
                    "content": (
                        f"/no_think\n{user_prompt}\n"
                        "Return only valid JSON. Do not include Markdown, comments, or trailing text.\n"
                        f'<video src="{video_input["url"]}" />'
                    ),
                },
            ],
            "temperature": 0.2,
            "max_tokens": 1200,
            "response_format": {
                "type": "json_object",
            },
        }

    return {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": system_prompt,
            },
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": user_prompt,
                    },
                    {
                        "type": "video_url",
                        "video_url": {
                            "url": video_input["url"],
                        },
                    },
                ],
            },
        ],
        "temperature": 0.2,
        "max_tokens": 1200,
        "response_format": {
            "type": "json_object",
        },
    }


def build_nvidia_video_input_from_local_file(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    video_path: Path,
    content_type: str,
    description: str,
) -> Dict[str, str | None]:
    video_size = video_path.stat().st_size
    max_size = resolve_video_max_size_bytes()
    if video_size > max_size:
        raise ValueError(
            "Video file exceeds VIDEO_LLM_MAX_VIDEO_SIZE_MB "
            f"({video_size} bytes > {max_size} bytes)."
        )

    if video_size <= NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES:
        encoded = base64.b64encode(video_path.read_bytes()).decode("ascii")
        return {
            "url": f"data:{content_type};base64,{encoded}",
            "asset_id": None,
            "content_type": content_type,
        }

    asset_id, upload_url = create_nvidia_asset(
        client,
        api_key,
        asset_base_url,
        content_type,
        description,
    )
    try:
        upload_video_to_asset(client, upload_url, video_path, content_type, description)
    except Exception:
        delete_nvidia_asset(client, api_key, asset_base_url, asset_id)
        raise

    return {
        "url": f"data:{content_type};asset_id,{asset_id}",
        "asset_id": asset_id,
        "content_type": content_type,
    }


# 이보다 짧은 영상에는 3구간 강제 분할을 적용하지 않습니다. 원래 3구간 강제 프롬프트는
# 60초/120초 영상에서 모델이 관찰을 [0, duration] 하나로 뭉개버리는 문제(구간 분할
# 실패)에 대응하려고 도입했습니다(docs/service-plan/video-llm-model-options.md 5절
# 측정 참고). 하지만 구간 분할(chunking) 도입 이후에는 이 프롬프트가 이미 짧은
# 세그먼트(예: 10~20초)에도 그대로 적용되어, 오히려 실제로는 하나로 이어지는 행동을
# 억지로 3조각으로 쪼개 답하게 만들 위험이 있습니다. 짧은 영상/세그먼트는 모델이
# 실제로 관찰한 시점을 그대로 보고하게 하는 쪽이 더 정직한 결과를 기대할 수 있습니다.
MIN_DURATION_FOR_FORCED_SEGMENTATION_SEC = 30.0


def build_duration_prompt(duration_sec: float | None) -> str:
    if duration_sec is None:
        return ""

    if duration_sec < MIN_DURATION_FOR_FORCED_SEGMENTATION_SEC:
        return (
            f"The video is exactly {duration_sec:.3f} seconds long. "
            f"All startSec and endSec values must be within [0, {duration_sec:.3f}]. "
            "Report the real moments you actually observe with their true timestamps. "
            "Do not force the video into artificial sub-segments: if a behavior genuinely "
            f"spans the whole clip, report one observation covering [0, {duration_sec:.3f}]; "
            "if distinct moments are visible, report them separately with their actual timing. "
        )

    first_boundary = duration_sec / 3
    second_boundary = duration_sec * 2 / 3
    return (
        f"The video is exactly {duration_sec:.3f} seconds long. "
        f"All startSec and endSec values must be within [0, {duration_sec:.3f}] "
        "and must not all be 0 unless the entire observation truly spans the whole video. "
        f"Divide the video into three temporal segments: [0, {first_boundary:.3f}), "
        f"[{first_boundary:.3f}, {second_boundary:.3f}), "
        f"and [{second_boundary:.3f}, {duration_sec:.3f}]. "
        "For each observation category (eyeContact, facialExpression, gesture, posture), "
        "include at least one observation for each segment when the behavior is actually visible "
        "in that segment. Unless the behavior truly does not change for the whole video, "
        "do not collapse all observations into a single [0, duration] range. "
    )


VIDEO_DOWNLOAD_TIMEOUT_SECONDS = 60.0


@contextmanager
def resolve_video_file(request: VideoLlmAnalysisRequest) -> Iterator[tuple[Path, str]]:
    """MinIO 영상은 임시 파일로 스트리밍하고 사용 직후 삭제합니다.

    URL 다운로드가 실패하면 공유 스토리지의 기존 로컬 경로를 사용합니다.
    """
    downloaded = None
    if request.videoDownloadUrl:
        downloaded = download_video_to_temp_file(
            request.jobId,
            request.videoDownloadUrl,
            request.videoPath,
        )

    if downloaded is None:
        local_path = Path(request.videoPath)
        validate_local_video_path(local_path)
        content_type = mimetypes.guess_type(local_path.name)[0] or "video/mp4"
        yield local_path, content_type
        return

    downloaded_path, content_type = downloaded
    try:
        yield downloaded_path, content_type
    finally:
        downloaded_path.unlink(missing_ok=True)


def resolve_allowed_download_hosts() -> set[str]:
    return set(get_settings().allowed_download_hosts)


def _download_url_host_port(parsed) -> str:
    default_port = 443 if parsed.scheme == "https" else 80
    port = parsed.port or default_port
    return f"{parsed.hostname}:{port}".lower()


# videoDownloadUrl(backend가 만드는 MinIO presigned URL)이 신뢰할 수 있는 내부 MinIO
# 엔드포인트만 가리키도록 강제한다. scheme/netloc만 확인하던 기존 검증은 backend가
# 침해되거나 버그로 임의 URL을 보낼 경우 이 엔진이 내부망 스캔이나 클라우드 메타데이터
# 엔드포인트 접근에 악용되는 것을 막지 못했다(2026-07-23 코드 리뷰 P1-04). 이 값은 항상
# 고정된 MinIO 엔드포인트 하나만 가리켜야 정상이므로, host:port 허용 목록으로 제한한다
# (analysis-engine의 app/core/network_security.py와 동일한 검증 방식).
def validate_video_download_url(video_download_url: str) -> None:
    parsed = urlparse(video_download_url)

    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError(
            f"videoDownloadUrl must be an absolute http(s) URL, got {video_download_url!r}."
        )

    allowed_hosts = resolve_allowed_download_hosts()
    host_port = _download_url_host_port(parsed)

    if host_port not in allowed_hosts:
        raise ValueError(
            f"videoDownloadUrl host {host_port!r} is not in VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS "
            f"({sorted(allowed_hosts)!r})."
        )


def download_video_to_temp_file(
    job_id: str,
    video_download_url: str,
    original_video_path: str,
) -> tuple[Path, str] | None:
    suffix = Path(original_video_path).suffix or ".mp4"
    temp_path: Path | None = None
    max_size = resolve_video_max_size_bytes()

    try:
        validate_video_download_url(video_download_url)

        with tempfile.NamedTemporaryFile(
            prefix=f"video-llm-{job_id}-",
            suffix=suffix,
            delete=False,
        ) as temp_file:
            temp_path = Path(temp_file.name)

            # follow_redirects=False(명시): 리다이렉트를 자동으로 따라가면 허용 목록
            # 검증을 우회해 다른 호스트로 요청이 새어나갈 수 있으므로, 라이브러리 기본값에
            # 기대는 대신 명시적으로 끄고 리다이렉트 응답 자체를 아래에서 거부한다.
            with httpx.Client(
                timeout=VIDEO_DOWNLOAD_TIMEOUT_SECONDS, follow_redirects=False
            ) as client:
                with client.stream("GET", video_download_url) as response:
                    if 300 <= response.status_code < 400:
                        raise ValueError(
                            f"videoDownloadUrl returned a redirect ({response.status_code}), "
                            "which is not allowed."
                        )

                    response.raise_for_status()
                    content_length = response.headers.get("content-length")
                    if content_length is not None and int(content_length) > max_size:
                        raise ValueError(
                            "Downloaded video exceeds VIDEO_LLM_MAX_VIDEO_SIZE_MB "
                            f"({content_length} bytes > {max_size} bytes)."
                        )

                    downloaded_size = 0
                    for chunk in response.iter_bytes(chunk_size=VIDEO_STREAM_CHUNK_SIZE_BYTES):
                        if not chunk:
                            continue
                        downloaded_size += len(chunk)
                        if downloaded_size > max_size:
                            raise ValueError(
                                "Downloaded video exceeds VIDEO_LLM_MAX_VIDEO_SIZE_MB "
                                f"({downloaded_size} bytes > {max_size} bytes)."
                            )
                        temp_file.write(chunk)

                    if downloaded_size == 0:
                        raise ValueError("Downloaded video is empty.")

                    content_type = response.headers.get("content-type") or "video/mp4"

        return temp_path, content_type

    except Exception as exception:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
        logger.warning(
            "(%s) MinIO 다운로드 URL 요청 실패, 로컬 경로로 폴백합니다: %s",
            job_id,
            exception,
        )
        return None




def create_nvidia_asset(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    content_type: str,
    description: str,
) -> tuple[str, str]:
    response = client.post(
        f"{asset_base_url}/assets",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "contentType": content_type,
            "description": description,
        },
    )
    response.raise_for_status()
    response_json = response.json()

    asset_id = response_json.get("assetId")
    upload_url = response_json.get("uploadUrl")
    if not isinstance(asset_id, str) or not asset_id.strip():
        raise ValueError("NVIDIA asset create response is missing assetId.")
    if not isinstance(upload_url, str) or not upload_url.strip():
        raise ValueError("NVIDIA asset create response is missing uploadUrl.")

    return asset_id.strip(), upload_url.strip()


def upload_video_to_asset(
    client: httpx.Client,
    upload_url: str,
    video_path: Path,
    content_type: str,
    description: str,
) -> None:
    video_size = video_path.stat().st_size
    response = client.put(
        upload_url,
        headers={
            "Content-Type": content_type,
            "Content-Length": str(video_size),
            "x-amz-meta-nvcf-asset-description": description,
        },
        content=iter_file_chunks(video_path),
    )
    response.raise_for_status()


def iter_file_chunks(video_path: Path) -> Iterator[bytes]:
    with video_path.open("rb") as video_file:
        while chunk := video_file.read(VIDEO_STREAM_CHUNK_SIZE_BYTES):
            yield chunk


def delete_nvidia_asset(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    asset_id: str,
) -> None:
    try:
        response = client.delete(
            f"{asset_base_url}/assets/{asset_id}",
            headers={
                "Authorization": f"Bearer {api_key}",
            },
        )
        response.raise_for_status()
    except Exception:
        logger.warning(
            "NVIDIA_VIDEO_LLM_ASSET_CLEANUP_FAILED assetId=%s",
            asset_id,
            exc_info=True,
        )


def poll_nvidia_chat_completion_result(
    client: httpx.Client,
    base_url: str,
    api_key: str,
    initial_response: httpx.Response,
) -> httpx.Response:
    request_id = extract_nvidia_request_id(initial_response)
    poll_url = f"{base_url}/status/{request_id}"

    for _ in range(NVIDIA_STATUS_POLL_MAX_ATTEMPTS):
        time.sleep(NVIDIA_STATUS_POLL_INTERVAL_SECONDS)
        poll_response = client.get(
            poll_url,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Accept": "application/json",
            },
        )
        if poll_response.status_code == 200:
            return poll_response
        if poll_response.status_code == 202:
            continue
        poll_response.raise_for_status()

    raise TimeoutError(
        "NVIDIA chat completion result did not finish within "
        f"{NVIDIA_STATUS_POLL_MAX_ATTEMPTS} polling attempts."
    )


def extract_nvidia_request_id(response: httpx.Response) -> str:
    try:
        response_json = response.json()
    except json.JSONDecodeError as exc:
        raise ValueError("NVIDIA 202 response body is not valid JSON.") from exc

    if not isinstance(response_json, dict):
        raise ValueError("NVIDIA 202 response body must be a JSON object.")

    request_id = response_json.get("requestId")
    if not isinstance(request_id, str) or not request_id.strip():
        raise ValueError("NVIDIA 202 response is missing requestId.")

    return request_id.strip()


def extract_chat_completion_content(response_json: Dict[str, Any]) -> str:
    try:
        content = response_json["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError("NVIDIA response is missing choices[0].message.content.") from exc

    if isinstance(content, str):
        return content

    if isinstance(content, list):
        text_parts = [
            part.get("text", "")
            for part in content
            if isinstance(part, dict) and part.get("type") == "text"
        ]
        joined = "".join(text_parts).strip()
        if joined:
            return joined

    raise ValueError("NVIDIA response content must be a JSON string.")


def parse_model_json(content: str) -> Dict[str, Any]:
    stripped = content.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        stripped = "\n".join(lines).strip()

    try:
        parsed = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise ValueError("NVIDIA response content is not valid JSON.") from exc

    if not isinstance(parsed, dict):
        raise ValueError("NVIDIA response JSON must be an object.")

    return parsed


def normalize_video_llm_response(
    job_id: str,
    model_name: str,
    model_json: Dict[str, Any],
    duration_sec: float | None = None,
) -> Dict[str, Any]:
    observations = model_json.get("observations")
    if not isinstance(observations, dict):
        raise ValueError("NVIDIA response is missing observations object.")

    normalized_observations = {
        category: normalize_observation_list(observations, category, duration_sec)
        for category in OBSERVATION_CATEGORIES
    }

    global_summary = model_json.get("globalSummary")
    if not isinstance(global_summary, dict):
        raise ValueError("NVIDIA response is missing globalSummary object.")

    normalized_summary = {}
    for field in SUMMARY_FIELDS:
        value = global_summary.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(
                f"NVIDIA response globalSummary.{field} must be a non-empty string."
            )
        normalized_summary[field] = value.strip()

    return {
        "jobId": job_id,
        "status": "success",
        "model": {
            "name": model_name,
            "version": "nvidia-nim",
            "generationMode": "REAL",
        },
        "observations": normalized_observations,
        "globalSummary": normalized_summary,
    }


def normalize_observation_list(
    observations: Dict[str, Any],
    category: str,
    duration_sec: float | None,
) -> list[Dict[str, Any]]:
    items = observations.get(category)
    if not isinstance(items, list):
        raise ValueError(f"NVIDIA response observations.{category} must be a list.")

    return [
        normalize_observation_item(item, category, index, duration_sec)
        for index, item in enumerate(items)
    ]


def normalize_observation_item(
    item: Any,
    category: str,
    index: int,
    duration_sec: float | None,
) -> Dict[str, Any]:
    if not isinstance(item, dict):
        raise ValueError(f"NVIDIA response observations.{category}[{index}] must be an object.")

    raw_start_sec = require_number(item, "startSec", category, index)
    raw_end_sec = require_number(item, "endSec", category, index)

    # 순서 검증은 클램프 전(원본 값) 기준으로 합니다. 예를 들어 startSec=-1.0,
    # endSec=-3.5(둘 다 음수, 순서도 뒤집힘)는 각각 0으로 클램프되면 0 < 0이 되어
    # "정상"처럼 보이지만, 실제로는 애초에 유효하지 않았던 값입니다. 클램프 결과가
    # 우연히 뒤집힌 순서를 가려버리지 않도록 원본 값으로 먼저 걸러냅니다.
    if raw_end_sec < raw_start_sec:
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}] has endSec < startSec."
        )

    start_sec = clamp_observation_time(raw_start_sec, duration_sec, category, index, "startSec")
    end_sec = clamp_observation_time(raw_end_sec, duration_sec, category, index, "endSec")

    label = require_string(item, "label", category, index)
    description = require_string(item, "description", category, index)
    confidence = require_number(item, "confidence", category, index)
    if confidence < 0 or confidence > 1:
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].confidence must be between 0 and 1."
        )

    return {
        "startSec": start_sec,
        "endSec": end_sec,
        "label": label,
        "description": description,
        "confidence": confidence,
    }


def clamp_observation_time(
    value: int | float,
    duration_sec: float | None,
    category: str,
    index: int,
    field: str,
) -> int | float:
    if value < 0:
        logger.warning(
            "NVIDIA_VIDEO_LLM_TIME_CLAMP category=%s index=%s field=%s original=%s durationSec=%s reason=negative",
            category,
            index,
            field,
            value,
            duration_sec,
        )
        value = 0

    if duration_sec is None or value <= duration_sec:
        return value

    logger.warning(
        "NVIDIA_VIDEO_LLM_TIME_CLAMP category=%s index=%s field=%s original=%s durationSec=%s",
        category,
        index,
        field,
        value,
        duration_sec,
    )
    return duration_sec


def require_number(item: Dict[str, Any], field: str, category: str, index: int) -> int | float:
    value = item.get(field)
    # json.loads()는 표준 JSON 사양 밖의 확장 리터럴인 NaN/Infinity/-Infinity를 기본적으로
    # 허용한다. 이런 값은 모든 비교 연산에서 항상 False가 되어 clamp_observation_time의
    # 상/하한 검사와 confidence 범위 검사를 그대로 통과해버리므로, 검증 없이 최종 응답에
    # 그대로 실려 backend로 전달된다(엄격한 JSON 파서에서 파싱 실패를 유발할 수 있고,
    # 원래 이 값들이 걸러졌어야 할 mock FALLBACK 경로도 타지 않는다). 여기서 명시적으로
    # 걸러낸다.
    if (
        not isinstance(value, (int, float))
        or isinstance(value, bool)
        or not math.isfinite(value)
    ):
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].{field} must be a finite number."
        )
    return value


def require_string(item: Dict[str, Any], field: str, category: str, index: int) -> str:
    value = item.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].{field} must be a non-empty string."
        )
    return value.strip()


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
