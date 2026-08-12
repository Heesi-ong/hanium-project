from contextlib import contextmanager
import logging
import mimetypes
from pathlib import Path
import tempfile
from typing import Iterator, Protocol
from urllib.parse import urlparse

import httpx

from app.core.settings import get_settings
from app.services import deadline

logger = logging.getLogger("video-llm-engine")

VIDEO_STREAM_CHUNK_SIZE_BYTES = 1024 * 1024


class VideoFileRequest(Protocol):
    jobId: str
    videoPath: str
    videoDownloadUrl: str | None


def resolve_video_max_size_bytes() -> int:
    return get_settings().max_video_size_bytes


def resolve_allowed_video_base_dir() -> Path:
    return get_settings().allowed_video_base_dir


# request.videoPath는 backend가 만들어 보내는 값이라 지금은 항상 안전한 경로만 들어오지만,
# analysis-engine의 job_id 검증(경로 이탈 방지)과 같은 이유로 이 엔진도 방어적으로 한 번 더
# 검증합니다. 이 경로의 내용은 그대로 읽혀 제3자(NVIDIA)로 업로드되므로, backend가 버그나
# 침해로 잘못된 경로를 보내는 경우 임의 파일이 외부로 유출되는 것을 막습니다.
def validate_local_video_path(video_path: Path) -> None:
    allowed_base_dir = resolve_allowed_video_base_dir()
    resolved_path = video_path.resolve()

    if (
        resolved_path != allowed_base_dir
        and allowed_base_dir not in resolved_path.parents
    ):
        raise ValueError(
            f"videoPath must be inside {allowed_base_dir}, got {video_path!r} "
            f"(resolved to {resolved_path})."
        )


VIDEO_DOWNLOAD_TIMEOUT_SECONDS = 60.0


@contextmanager
def resolve_video_file(
    request: VideoFileRequest,
    deadline_monotonic: float | None = None,
) -> Iterator[tuple[Path, str]]:
    """MinIO 영상은 임시 파일로 스트리밍하고 사용 직후 삭제합니다.

    URL 다운로드가 실패하면 공유 스토리지의 기존 로컬 경로를 사용합니다.
    """
    downloaded = None
    if request.videoDownloadUrl:
        downloaded = download_video_to_temp_file(
            request.jobId,
            request.videoDownloadUrl,
            request.videoPath,
            deadline_monotonic,
        )

    if downloaded is None:
        deadline.ensure_within(deadline_monotonic, "video_file_resolution")
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
    deadline_monotonic: float | None = None,
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
            effective_download_timeout_seconds = deadline.remaining_timeout_seconds(
                deadline_monotonic,
                VIDEO_DOWNLOAD_TIMEOUT_SECONDS,
                "video_download",
            )
            with httpx.Client(
                timeout=effective_download_timeout_seconds, follow_redirects=False
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
                    for chunk in response.iter_bytes(
                        chunk_size=VIDEO_STREAM_CHUNK_SIZE_BYTES
                    ):
                        deadline.ensure_within(deadline_monotonic, "video_download")
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
