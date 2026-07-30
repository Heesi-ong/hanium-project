import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit


NVIDIA_DEFAULT_API_BASE_URL = "https://integrate.api.nvidia.com/v1"
NVIDIA_DEFAULT_ASSET_BASE_URL = "https://api.nvcf.nvidia.com/v2/nvcf"
NVIDIA_DEFAULT_MODEL = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
VALID_VIDEO_LLM_BACKENDS = frozenset({"mock", "external-api", "local-model"})
VALID_VIDEO_LLM_POLICIES = frozenset({"STRICT", "DEGRADED", "DISABLED"})


class SettingsError(RuntimeError, ValueError):
    """Raised when a Video LLM environment variable is invalid."""


def _value_or_default(
    environment: Mapping[str, str],
    name: str,
    default: str,
) -> str:
    value = environment.get(name, default).strip()
    return value or default


def _strict_bool(environment: Mapping[str, str], name: str, default: bool) -> bool:
    raw_value = environment.get(name, str(default).lower()).strip().lower()
    if raw_value == "true":
        return True
    if raw_value == "false":
        return False
    raise SettingsError(
        f"{name} must be either 'true' or 'false', got {raw_value!r}."
    )


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


def _absolute_http_url(
    environment: Mapping[str, str],
    name: str,
    default: str,
) -> str:
    value = _value_or_default(environment, name, default).rstrip("/")
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or not parsed.hostname:
        raise SettingsError(f"{name} must be an absolute http(s) URL.")
    try:
        parsed.port
    except ValueError as exception:
        raise SettingsError(f"{name} contains an invalid port.") from exception
    if parsed.username is not None or parsed.password is not None:
        raise SettingsError(f"{name} must not contain URL credentials.")
    if parsed.query or parsed.fragment:
        raise SettingsError(f"{name} must not contain a query or fragment.")
    return value


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
class VideoLlmSettings:
    internal_engine_api_key: str
    log_dir: Path
    enabled: bool
    policy: str
    installed_backend: str
    nvidia_api_key: str
    nvidia_model: str
    nvidia_api_base_url: str
    nvidia_asset_api_base_url: str
    nvidia_timeout_seconds: float
    total_timeout_seconds: float
    max_video_size_mb: int
    allowed_video_base_dir: Path
    allowed_download_hosts: frozenset[str]
    real_model_max_concurrency: int
    real_model_semaphore_timeout_seconds: float
    chunk_duration_seconds: float
    segment_split_timeout_seconds: float
    max_duration_seconds: float

    @property
    def max_video_size_bytes(self) -> int:
        return self.max_video_size_mb * 1024 * 1024

    @classmethod
    def from_env(
        cls,
        environment: Mapping[str, str] | None = None,
    ) -> "VideoLlmSettings":
        env = os.environ if environment is None else environment
        legacy_enabled = _strict_bool(env, "VIDEO_LLM_ENABLED", False)
        raw_policy = env.get("VIDEO_LLM_POLICY", "").strip().upper()
        policy = raw_policy or ("DEGRADED" if legacy_enabled else "DISABLED")
        if policy not in VALID_VIDEO_LLM_POLICIES:
            raise SettingsError(
                "VIDEO_LLM_POLICY must be one of "
                f"{sorted(VALID_VIDEO_LLM_POLICIES)!r}, got {policy!r}."
            )
        enabled = policy != "DISABLED"
        installed_backend = _value_or_default(
            env, "VIDEO_LLM_BACKEND", "mock"
        ).lower()
        if installed_backend not in VALID_VIDEO_LLM_BACKENDS:
            raise SettingsError(
                "VIDEO_LLM_BACKEND must be one of "
                f"{sorted(VALID_VIDEO_LLM_BACKENDS)!r}, got {installed_backend!r}."
            )
        if enabled and installed_backend != "external-api":
            raise SettingsError(
                "VIDEO_LLM_POLICY=STRICT or DEGRADED requires "
                "VIDEO_LLM_BACKEND=external-api; "
                f"got {installed_backend!r}."
            )

        nvidia_api_key = env.get("NVIDIA_API_KEY", "").strip()
        if enabled and not nvidia_api_key:
            raise SettingsError(
                "VIDEO_LLM_POLICY=STRICT or DEGRADED but NVIDIA_API_KEY is missing"
            )

        chunk_duration_seconds = _positive_float(
            env, "VIDEO_LLM_CHUNK_DURATION_SECONDS", 100
        )
        max_duration_seconds = _positive_float(
            env, "VIDEO_LLM_MAX_DURATION_SECONDS", 7200
        )
        if chunk_duration_seconds > max_duration_seconds:
            raise SettingsError(
                "VIDEO_LLM_CHUNK_DURATION_SECONDS must be less than or equal to "
                "VIDEO_LLM_MAX_DURATION_SECONDS."
            )

        return cls(
            internal_engine_api_key=env.get("INTERNAL_ENGINE_API_KEY", "").strip(),
            log_dir=_path(
                env,
                "LOG_DIR",
                "../storage/logs",
                require_absolute=False,
            ),
            enabled=enabled,
            policy=policy,
            installed_backend=installed_backend,
            nvidia_api_key=nvidia_api_key,
            nvidia_model=_value_or_default(
                env, "NVIDIA_VIDEO_LLM_MODEL", NVIDIA_DEFAULT_MODEL
            ),
            nvidia_api_base_url=_absolute_http_url(
                env, "NVIDIA_API_BASE_URL", NVIDIA_DEFAULT_API_BASE_URL
            ),
            nvidia_asset_api_base_url=_absolute_http_url(
                env,
                "NVIDIA_ASSET_API_BASE_URL",
                NVIDIA_DEFAULT_ASSET_BASE_URL,
            ),
            nvidia_timeout_seconds=_positive_float(
                env, "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", 120
            ),
            total_timeout_seconds=_positive_float(
                env, "VIDEO_LLM_TOTAL_TIMEOUT_SECONDS", 540
            ),
            max_video_size_mb=_positive_int(
                env, "VIDEO_LLM_MAX_VIDEO_SIZE_MB", 500
            ),
            allowed_video_base_dir=_path(
                env,
                "VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR",
                "/storage",
                require_absolute=True,
            ),
            allowed_download_hosts=_host_port_allowlist(
                env, "VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS", "minio:9000"
            ),
            real_model_max_concurrency=_positive_int(
                env, "VIDEO_LLM_REAL_MODEL_MAX_CONCURRENCY", 3
            ),
            real_model_semaphore_timeout_seconds=_positive_float(
                env,
                "VIDEO_LLM_REAL_MODEL_SEMAPHORE_TIMEOUT_SECONDS",
                30,
            ),
            chunk_duration_seconds=chunk_duration_seconds,
            segment_split_timeout_seconds=_positive_float(
                env, "VIDEO_LLM_SEGMENT_SPLIT_TIMEOUT_SECONDS", 30
            ),
            max_duration_seconds=max_duration_seconds,
        )


_active_settings: VideoLlmSettings | None = None


def install_settings(settings: VideoLlmSettings) -> None:
    global _active_settings
    _active_settings = settings


def clear_settings() -> None:
    global _active_settings
    _active_settings = None


def get_settings() -> VideoLlmSettings:
    return _active_settings or VideoLlmSettings.from_env()
