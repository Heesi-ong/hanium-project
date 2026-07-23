import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit


class SettingsError(RuntimeError, ValueError):
    """Raised when an engine environment variable is invalid."""


def _positive_int(environment: Mapping[str, str], name: str, default: int) -> int:
    raw_value = environment.get(name, str(default)).strip()
    try:
        value = int(raw_value)
    except ValueError as exception:
        raise SettingsError(
            f"{name} must be a positive integer, got {raw_value!r}."
        ) from exception

    if value < 1:
        raise SettingsError(
            f"{name} must be a positive integer, got {raw_value!r}."
        )
    return value


def _positive_float(environment: Mapping[str, str], name: str, default: float) -> float:
    raw_value = environment.get(name, str(default)).strip()
    try:
        value = float(raw_value)
    except ValueError as exception:
        raise SettingsError(
            f"{name} must be a positive number, got {raw_value!r}."
        ) from exception

    if not math.isfinite(value) or value <= 0:
        raise SettingsError(f"{name} must be a positive number, got {raw_value!r}.")
    return value


def _path(
    environment: Mapping[str, str],
    name: str,
    default: str,
    *,
    require_absolute: bool,
) -> Path:
    raw_value = environment.get(name, default).strip()
    if not raw_value:
        raise SettingsError(f"{name} must be a non-empty absolute path.")

    path = Path(raw_value)
    if require_absolute and not path.is_absolute():
        raise SettingsError(f"{name} must be an absolute path, got {raw_value!r}.")
    return path.resolve()


def _host_port_allowlist(
    environment: Mapping[str, str],
    name: str,
    default: str,
) -> frozenset[str]:
    raw_value = environment.get(name, default)
    entries = [entry.strip().lower() for entry in raw_value.split(",") if entry.strip()]
    if not entries:
        raise SettingsError(
            f"{name} must contain at least one host:port entry, got {raw_value!r}."
        )

    normalized: set[str] = set()
    for entry in entries:
        parsed = urlsplit(f"//{entry}")
        try:
            port = parsed.port
        except ValueError as exception:
            raise SettingsError(
                f"{name} contains an invalid host:port entry {entry!r}."
            ) from exception

        if (
            not parsed.hostname
            or port is None
            or parsed.username is not None
            or parsed.password is not None
            or parsed.path
            or parsed.query
            or parsed.fragment
        ):
            raise SettingsError(
                f"{name} contains an invalid host:port entry {entry!r}."
            )
        normalized.add(f"{parsed.hostname.lower()}:{port}")

    return frozenset(normalized)


@dataclass(frozen=True)
class AnalysisEngineSettings:
    internal_engine_api_key: str
    log_dir: Path
    whisper_pool_size: int
    pose_pool_size: int
    face_pool_size: int
    max_video_size_mb: int
    whisper_transcribe_timeout_seconds: float
    allowed_download_hosts: frozenset[str]
    allowed_video_base_dir: Path

    @property
    def max_video_size_bytes(self) -> int:
        return self.max_video_size_mb * 1024 * 1024

    @classmethod
    def from_env(
        cls,
        environment: Mapping[str, str] | None = None,
    ) -> "AnalysisEngineSettings":
        env = os.environ if environment is None else environment
        return cls(
            internal_engine_api_key=env.get("INTERNAL_ENGINE_API_KEY", "").strip(),
            log_dir=_path(
                env,
                "LOG_DIR",
                "../storage/logs",
                require_absolute=False,
            ),
            whisper_pool_size=_positive_int(
                env, "ANALYSIS_ENGINE_WHISPER_POOL_SIZE", 2
            ),
            pose_pool_size=_positive_int(env, "ANALYSIS_ENGINE_POSE_POOL_SIZE", 2),
            face_pool_size=_positive_int(env, "ANALYSIS_ENGINE_FACE_POOL_SIZE", 2),
            max_video_size_mb=_positive_int(
                env, "ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB", 500
            ),
            whisper_transcribe_timeout_seconds=_positive_float(
                env,
                "ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS",
                600,
            ),
            allowed_download_hosts=_host_port_allowlist(
                env,
                "ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS",
                "minio:9000",
            ),
            allowed_video_base_dir=_path(
                env,
                "ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR",
                "/storage",
                require_absolute=True,
            ),
        )


_active_settings: AnalysisEngineSettings | None = None


def install_settings(settings: AnalysisEngineSettings) -> None:
    global _active_settings
    _active_settings = settings


def clear_settings() -> None:
    global _active_settings
    _active_settings = None


def get_settings() -> AnalysisEngineSettings:
    # Requests use the immutable snapshot installed by FastAPI lifespan. Direct
    # unit calls outside a running app still receive a freshly validated object.
    return _active_settings or AnalysisEngineSettings.from_env()
