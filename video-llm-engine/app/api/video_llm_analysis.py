import base64
from contextlib import contextmanager
import json
import logging
import mimetypes
import os
from pathlib import Path
import tempfile
import time
from typing import Any, Dict, Iterator

import httpx
from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel

from app.core.logging_config import bind_job_id, bind_request_id
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
    durationSec: float | None = None
    videoDownloadUrl: str | None = None


def resolve_video_llm_enabled() -> bool:
    return os.getenv("VIDEO_LLM_ENABLED", "false").strip().lower() == "true"


# 이 이미지가 어떤 의존성 세트로 빌드되었는지(Dockerfile의 VIDEO_LLM_BACKEND build arg와
# 짝을 이룹니다). mock/external-api는 requirements-base.txt만으로 충분하고,
# local-model만 무거운 torch/transformers 스택(requirements-real-model.txt)이 필요합니다.
# 런타임 동작 자체는 여전히 VIDEO_LLM_ENABLED(위)로 켜고 끕니다 — 이 값은 "실제로 어떤
# 방식의 구현을 기대할 수 있는 이미지인지"를 알려주는 관측용 정보입니다.
def resolve_video_llm_backend() -> str:
    return os.getenv("VIDEO_LLM_BACKEND", "mock").strip().lower()


NVIDIA_DEFAULT_API_BASE_URL = "https://integrate.api.nvidia.com/v1"
NVIDIA_DEFAULT_ASSET_BASE_URL = "https://api.nvcf.nvidia.com/v2/nvcf"
NVIDIA_DEFAULT_MODEL = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES = 180 * 1024
VIDEO_STREAM_CHUNK_SIZE_BYTES = 1024 * 1024
DEFAULT_VIDEO_MAX_SIZE_MB = 500
NVIDIA_STATUS_POLL_MAX_ATTEMPTS = 10
NVIDIA_STATUS_POLL_INTERVAL_SECONDS = 1
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
    raw_value = os.getenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", str(DEFAULT_VIDEO_MAX_SIZE_MB)).strip()
    try:
        max_size_mb = int(raw_value)
    except ValueError as exception:
        raise RuntimeError("VIDEO_LLM_MAX_VIDEO_SIZE_MB must be a positive integer.") from exception

    if max_size_mb <= 0:
        raise RuntimeError("VIDEO_LLM_MAX_VIDEO_SIZE_MB must be a positive integer.")

    return max_size_mb * 1024 * 1024


def call_real_video_llm_model(request: VideoLlmAnalysisRequest) -> Dict[str, Any]:
    api_key = os.getenv("NVIDIA_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("NVIDIA_API_KEY is required when VIDEO_LLM_ENABLED=true.")

    model = (
        os.getenv("NVIDIA_VIDEO_LLM_MODEL", NVIDIA_DEFAULT_MODEL).strip()
        or NVIDIA_DEFAULT_MODEL
    )
    base_url = (
        os.getenv("NVIDIA_API_BASE_URL", NVIDIA_DEFAULT_API_BASE_URL).strip()
        or NVIDIA_DEFAULT_API_BASE_URL
    ).rstrip("/")
    asset_base_url = (
        os.getenv("NVIDIA_ASSET_API_BASE_URL", NVIDIA_DEFAULT_ASSET_BASE_URL).strip()
        or NVIDIA_DEFAULT_ASSET_BASE_URL
    ).rstrip("/")
    timeout_seconds = float(os.getenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "120"))

    url = f"{base_url}/chat/completions"
    started_at = time.monotonic()
    response_status = "not_sent"
    asset_id = None

    try:
        with httpx.Client(timeout=timeout_seconds) as client:
            video_input = prepare_nvidia_video_input(
                client=client,
                api_key=api_key,
                asset_base_url=asset_base_url,
                request=request,
            )
            asset_id = video_input.get("asset_id")
            payload = build_nvidia_chat_completion_payload(request, model, video_input)
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
            request.jobId,
            model,
            response_status,
            elapsed_ms,
        )

    content = extract_chat_completion_content(response.json())
    model_json = parse_model_json(content)
    return normalize_video_llm_response(
        request.jobId,
        model,
        model_json,
        request.durationSec,
    )


def build_nvidia_chat_completion_payload(
    request: VideoLlmAnalysisRequest,
    model: str,
    video_input: Dict[str, str | None],
) -> Dict[str, Any]:
    system_prompt = (
        "/no_think\n"
        "You are a presentation-coaching video analyst. Return only strict JSON. "
        "Do not wrap the JSON in Markdown. The JSON must match the requested schema exactly."
    )
    duration_prompt = build_duration_prompt(request.durationSec)
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
        f"Sampling hint from caller: sampleFps={request.sampleFps}, maxFrames={request.maxFrames}."
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


def prepare_nvidia_video_input(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    request: VideoLlmAnalysisRequest,
) -> Dict[str, str | None]:
    with resolve_video_file(request) as (video_path, content_type):
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

        description = f"video-llm-analysis jobId={request.jobId}"
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


def build_duration_prompt(duration_sec: float | None) -> str:
    if duration_sec is None:
        return ""

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
        content_type = mimetypes.guess_type(local_path.name)[0] or "video/mp4"
        yield local_path, content_type
        return

    downloaded_path, content_type = downloaded
    try:
        yield downloaded_path, content_type
    finally:
        downloaded_path.unlink(missing_ok=True)


def download_video_to_temp_file(
    job_id: str,
    video_download_url: str,
    original_video_path: str,
) -> tuple[Path, str] | None:
    suffix = Path(original_video_path).suffix or ".mp4"
    temp_path: Path | None = None
    max_size = resolve_video_max_size_bytes()

    try:
        with tempfile.NamedTemporaryFile(
            prefix=f"video-llm-{job_id}-",
            suffix=suffix,
            delete=False,
        ) as temp_file:
            temp_path = Path(temp_file.name)

            with httpx.Client(timeout=VIDEO_DOWNLOAD_TIMEOUT_SECONDS) as client:
                with client.stream("GET", video_download_url) as response:
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


def read_video_bytes(video_path: str) -> tuple[bytes, str]:
    mime_type, _ = mimetypes.guess_type(video_path)
    if not mime_type:
        mime_type = "video/mp4"

    path = Path(video_path)
    max_size = resolve_video_max_size_bytes()
    video_size = path.stat().st_size
    if video_size > max_size:
        raise ValueError(
            "Video file exceeds VIDEO_LLM_MAX_VIDEO_SIZE_MB "
            f"({video_size} bytes > {max_size} bytes)."
        )

    video_bytes = path.read_bytes()

    return video_bytes, mime_type


def encode_video_as_data_url(video_path: str) -> str:
    video_bytes, mime_type = read_video_bytes(video_path)
    if len(video_bytes) > NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES:
        raise ValueError(
            "Video file is too large for inline NVIDIA payload "
            f"({len(video_bytes)} bytes > {NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES} bytes). "
            "Use NVCF Asset API upload for larger files."
        )

    encoded = base64.b64encode(video_bytes).decode("ascii")
    return f"data:{mime_type};base64,{encoded}"


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

    start_sec = clamp_observation_time(
        require_number(item, "startSec", category, index),
        duration_sec,
        category,
        index,
        "startSec",
    )
    end_sec = clamp_observation_time(
        require_number(item, "endSec", category, index),
        duration_sec,
        category,
        index,
        "endSec",
    )
    if end_sec < start_sec:
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}] has endSec < startSec."
        )

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
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].{field} must be a number."
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
