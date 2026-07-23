from pathlib import Path
from urllib.parse import urlparse

from app.core.settings import get_settings


def resolve_allowed_download_hosts() -> set[str]:
    return set(get_settings().allowed_download_hosts)


def _host_port(parsed) -> str:
    default_port = 443 if parsed.scheme == "https" else 80
    port = parsed.port or default_port
    return f"{parsed.hostname}:{port}".lower()


# videoDownloadUrl(backend가 만드는 MinIO presigned URL)이 신뢰할 수 있는 내부 MinIO
# 엔드포인트만 가리키도록 강제한다. 검증 없이 requests.get()에 그대로 넘기면, backend가
# 침해되거나 버그로 임의 URL을 보낼 경우 이 엔진이 내부망 스캔이나 클라우드 메타데이터
# 엔드포인트 접근에 악용될 수 있다(2026-07-23 코드 리뷰 P1-04). 이 값은 항상 고정된 MinIO
# 엔드포인트 하나만 가리켜야 정상이므로, 사설 IP 여부를 판별하는 대신(내부 MinIO 주소
# 자체가 사설 IP이므로 그 방식은 여기서는 맞지 않다) host:port 허용 목록으로 제한한다.
def validate_video_download_url(video_download_url: str) -> None:
    parsed = urlparse(video_download_url)

    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError(
            f"videoDownloadUrl must be an absolute http(s) URL, got {video_download_url!r}."
        )

    allowed_hosts = resolve_allowed_download_hosts()
    host_port = _host_port(parsed)

    if host_port not in allowed_hosts:
        raise ValueError(
            f"videoDownloadUrl host {host_port!r} is not in ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS "
            f"({sorted(allowed_hosts)!r})."
        )


def resolve_allowed_video_base_dir() -> Path:
    return get_settings().allowed_video_base_dir


# request.videoPath는 backend가 만들어 보내는 값이라 지금은 항상 안전한 경로만 들어오지만,
# video-llm-engine과 동일한 이유로(방어적 이중 검증) 이 엔진도 허용된 스토리지 루트 밖의
# 임의 경로를 읽지 못하게 막는다.
def validate_local_video_path(video_path: Path) -> None:
    allowed_base_dir = resolve_allowed_video_base_dir()
    resolved_path = video_path.resolve()

    if resolved_path != allowed_base_dir and allowed_base_dir not in resolved_path.parents:
        raise ValueError(
            f"videoPath must be inside {allowed_base_dir}, got {video_path!r} "
            f"(resolved to {resolved_path})."
        )
