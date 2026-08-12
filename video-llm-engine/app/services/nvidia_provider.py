import base64
from dataclasses import dataclass
import json
import logging
from pathlib import Path
import time
from typing import Any, Callable, Dict, Iterator

import httpx

from app.services import deadline, media_io

logger = logging.getLogger("video-llm-engine")

NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES = 180 * 1024
NVIDIA_STATUS_POLL_MAX_ATTEMPTS = 10
NVIDIA_STATUS_POLL_INTERVAL_SECONDS = 1


@dataclass(frozen=True)
class ChatCompletionResult:
    response_json: Dict[str, Any]
    response_status: str


def execute_chat_completion(
    *,
    api_key: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    job_id: str,
    model: str,
    video_path: Path,
    content_type: str,
    payload_builder: Callable[[Dict[str, str | None]], Dict[str, Any]],
    deadline_monotonic: float | None = None,
) -> ChatCompletionResult:
    asset_id = None
    response_status = "not_sent"
    started_at = time.monotonic()

    try:
        effective_request_timeout_seconds = deadline.remaining_timeout_seconds(
            deadline_monotonic,
            timeout_seconds,
            "nvidia_request",
        )
        with httpx.Client(timeout=effective_request_timeout_seconds) as client:
            video_input = build_nvidia_video_input_from_local_file(
                client=client,
                api_key=api_key,
                asset_base_url=asset_base_url,
                video_path=video_path,
                content_type=content_type,
                description=f"video-llm-analysis jobId={job_id}",
                timeout_seconds=timeout_seconds,
                deadline_monotonic=deadline_monotonic,
            )
            asset_id = video_input.get("asset_id")
            deadline.ensure_within(deadline_monotonic, "nvidia_asset_upload")
            headers = {
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            }
            if asset_id:
                headers["NVCF-INPUT-ASSET-REFERENCES"] = asset_id

            response = client.post(
                f"{base_url}/chat/completions",
                headers=headers,
                json=payload_builder(video_input),
                timeout=deadline.remaining_timeout_seconds(
                    deadline_monotonic,
                    timeout_seconds,
                    "nvidia_chat_completion",
                ),
            )
            response_status = str(response.status_code)
            if response.status_code == 202:
                response = poll_nvidia_chat_completion_result(
                    client=client,
                    base_url=base_url,
                    api_key=api_key,
                    initial_response=response,
                    timeout_seconds=timeout_seconds,
                    deadline_monotonic=deadline_monotonic,
                )
                response_status = f"202->{response.status_code}"
            else:
                response.raise_for_status()
            deadline.ensure_within(deadline_monotonic, "nvidia_response")
            return ChatCompletionResult(
                response_json=response.json(),
                response_status=response_status,
            )
    finally:
        if asset_id:
            try:
                cleanup_timeout_seconds = deadline.remaining_timeout_seconds(
                    deadline_monotonic,
                    timeout_seconds,
                    "nvidia_asset_cleanup",
                )
                with httpx.Client(timeout=cleanup_timeout_seconds) as cleanup_client:
                    delete_nvidia_asset(
                        cleanup_client,
                        api_key,
                        asset_base_url,
                        asset_id,
                        cleanup_timeout_seconds,
                        deadline_monotonic,
                    )
            except TimeoutError:
                logger.warning(
                    "NVIDIA_VIDEO_LLM_ASSET_CLEANUP_SKIPPED_DEADLINE assetId=%s",
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


def build_nvidia_video_input_from_local_file(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    video_path: Path,
    content_type: str,
    description: str,
    timeout_seconds: float = 120,
    deadline_monotonic: float | None = None,
) -> Dict[str, str | None]:
    video_size = video_path.stat().st_size
    max_size = media_io.resolve_video_max_size_bytes()
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
        timeout_seconds,
        deadline_monotonic,
    )
    try:
        upload_video_to_asset(
            client,
            upload_url,
            video_path,
            content_type,
            description,
            timeout_seconds,
            deadline_monotonic,
        )
    except Exception:
        try:
            delete_nvidia_asset(
                client,
                api_key,
                asset_base_url,
                asset_id,
                timeout_seconds,
                deadline_monotonic,
            )
        except TimeoutError:
            logger.warning(
                "NVIDIA_VIDEO_LLM_ASSET_CLEANUP_SKIPPED_DEADLINE assetId=%s",
                asset_id,
            )
        raise

    return {
        "url": f"data:{content_type};asset_id,{asset_id}",
        "asset_id": asset_id,
        "content_type": content_type,
    }


def create_nvidia_asset(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    content_type: str,
    description: str,
    timeout_seconds: float = 120,
    deadline_monotonic: float | None = None,
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
        timeout=deadline.remaining_timeout_seconds(
            deadline_monotonic,
            timeout_seconds,
            "nvidia_asset_create",
        ),
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
    timeout_seconds: float = 120,
    deadline_monotonic: float | None = None,
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
        timeout=deadline.remaining_timeout_seconds(
            deadline_monotonic,
            timeout_seconds,
            "nvidia_asset_upload",
        ),
    )
    response.raise_for_status()


def iter_file_chunks(video_path: Path) -> Iterator[bytes]:
    with video_path.open("rb") as video_file:
        while chunk := video_file.read(media_io.VIDEO_STREAM_CHUNK_SIZE_BYTES):
            yield chunk


def delete_nvidia_asset(
    client: httpx.Client,
    api_key: str,
    asset_base_url: str,
    asset_id: str,
    timeout_seconds: float = 120,
    deadline_monotonic: float | None = None,
) -> None:
    try:
        response = client.delete(
            f"{asset_base_url}/assets/{asset_id}",
            headers={
                "Authorization": f"Bearer {api_key}",
            },
            timeout=deadline.remaining_timeout_seconds(
                deadline_monotonic,
                timeout_seconds,
                "nvidia_asset_cleanup",
            ),
        )
        response.raise_for_status()
    except TimeoutError:
        raise
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
    timeout_seconds: float = 120,
    deadline_monotonic: float | None = None,
) -> httpx.Response:
    request_id = extract_nvidia_request_id(initial_response)
    poll_url = f"{base_url}/status/{request_id}"

    for _ in range(NVIDIA_STATUS_POLL_MAX_ATTEMPTS):
        poll_sleep_seconds = deadline.remaining_timeout_seconds(
            deadline_monotonic,
            NVIDIA_STATUS_POLL_INTERVAL_SECONDS,
            "nvidia_status_poll",
        )
        time.sleep(poll_sleep_seconds)
        deadline.ensure_within(deadline_monotonic, "nvidia_status_poll")
        poll_response = client.get(
            poll_url,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Accept": "application/json",
            },
            timeout=deadline.remaining_timeout_seconds(
                deadline_monotonic,
                timeout_seconds,
                "nvidia_status_poll_request",
            ),
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
