import pytest

from app.core.settings import SettingsError, VideoLlmSettings


def test_settings_loads_and_normalizes_all_engine_environment_values(tmp_path):
    settings = VideoLlmSettings.from_env(
        {
            "INTERNAL_ENGINE_API_KEY": " shared-secret ",
            "LOG_DIR": str(tmp_path / "logs"),
            "VIDEO_LLM_POLICY": "strict",
            "VIDEO_LLM_ENABLED": "true",
            "VIDEO_LLM_BACKEND": "EXTERNAL-API",
            "NVIDIA_API_KEY": " nvapi-key ",
            "NVIDIA_VIDEO_LLM_MODEL": " custom/model ",
            "NVIDIA_API_BASE_URL": "https://api.example.com/v1/",
            "NVIDIA_ASSET_API_BASE_URL": "https://assets.example.com/v2/",
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS": "45.5",
            "VIDEO_LLM_MAX_VIDEO_SIZE_MB": "600",
            "VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR": str(tmp_path),
            "VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS": "MinIO:9000, media:9443",
            "VIDEO_LLM_REAL_MODEL_MAX_CONCURRENCY": "4",
            "VIDEO_LLM_REAL_MODEL_SEMAPHORE_TIMEOUT_SECONDS": "15",
            "VIDEO_LLM_CHUNK_DURATION_SECONDS": "90",
            "VIDEO_LLM_SEGMENT_SPLIT_TIMEOUT_SECONDS": "20",
            "VIDEO_LLM_MAX_DURATION_SECONDS": "3600",
        }
    )

    assert settings.internal_engine_api_key == "shared-secret"
    assert settings.log_dir == (tmp_path / "logs").resolve()
    assert settings.enabled is True
    assert settings.policy == "STRICT"
    assert settings.installed_backend == "external-api"
    assert settings.nvidia_api_key == "nvapi-key"
    assert settings.nvidia_model == "custom/model"
    assert settings.nvidia_api_base_url == "https://api.example.com/v1"
    assert settings.nvidia_asset_api_base_url == "https://assets.example.com/v2"
    assert settings.nvidia_timeout_seconds == 45.5
    assert settings.max_video_size_bytes == 600 * 1024 * 1024
    assert settings.allowed_video_base_dir == tmp_path.resolve()
    assert settings.allowed_download_hosts == frozenset(
        {"minio:9000", "media:9443"}
    )
    assert settings.real_model_max_concurrency == 4
    assert settings.real_model_semaphore_timeout_seconds == 15
    assert settings.chunk_duration_seconds == 90
    assert settings.segment_split_timeout_seconds == 20
    assert settings.max_duration_seconds == 3600


@pytest.mark.parametrize("value", ["1", "yes", "enabled", ""])
def test_settings_rejects_non_boolean_enabled_values(value):
    with pytest.raises(SettingsError, match="VIDEO_LLM_ENABLED"):
        VideoLlmSettings.from_env({"VIDEO_LLM_ENABLED": value})


@pytest.mark.parametrize(
    "environment,expected_policy,expected_enabled",
    [
        ({}, "DISABLED", False),
        (
            {
                "VIDEO_LLM_ENABLED": "true",
                "VIDEO_LLM_BACKEND": "external-api",
                "NVIDIA_API_KEY": "nvapi-key",
            },
            "DEGRADED",
            True,
        ),
        ({"VIDEO_LLM_POLICY": "disabled", "VIDEO_LLM_ENABLED": "true"}, "DISABLED", False),
    ],
)
def test_settings_resolves_explicit_policy_before_legacy_enabled(
    environment,
    expected_policy,
    expected_enabled,
):
    settings = VideoLlmSettings.from_env(environment)

    assert settings.policy == expected_policy
    assert settings.enabled is expected_enabled


def test_settings_rejects_unknown_video_llm_policy():
    with pytest.raises(SettingsError, match="VIDEO_LLM_POLICY"):
        VideoLlmSettings.from_env({"VIDEO_LLM_POLICY": "best-effort"})


def test_settings_requires_api_key_when_real_mode_is_enabled():
    with pytest.raises(SettingsError, match="NVIDIA_API_KEY is missing"):
        VideoLlmSettings.from_env(
            {
                "VIDEO_LLM_ENABLED": "true",
                "VIDEO_LLM_BACKEND": "external-api",
            }
        )


@pytest.mark.parametrize("policy", ["STRICT", "DEGRADED"])
def test_settings_requires_real_backend_and_key_for_active_policy(policy):
    with pytest.raises(SettingsError, match="VIDEO_LLM_BACKEND=external-api"):
        VideoLlmSettings.from_env(
            {
                "VIDEO_LLM_POLICY": policy,
                "VIDEO_LLM_BACKEND": "mock",
                "NVIDIA_API_KEY": "nvapi-key",
            }
        )


@pytest.mark.parametrize("backend", ["mock", "local-model"])
def test_settings_requires_external_api_backend_for_real_mode(backend):
    with pytest.raises(SettingsError, match="requires VIDEO_LLM_BACKEND=external-api"):
        VideoLlmSettings.from_env(
            {
                "VIDEO_LLM_ENABLED": "true",
                "VIDEO_LLM_BACKEND": backend,
                "NVIDIA_API_KEY": "nvapi-key",
            }
        )


@pytest.mark.parametrize("value", ["unknown", "external"])
def test_settings_rejects_unknown_installed_backend(value):
    with pytest.raises(SettingsError, match="VIDEO_LLM_BACKEND"):
        VideoLlmSettings.from_env({"VIDEO_LLM_BACKEND": value})


def test_settings_treats_blank_installed_backend_as_mock():
    assert (
        VideoLlmSettings.from_env({"VIDEO_LLM_BACKEND": ""}).installed_backend
        == "mock"
    )


@pytest.mark.parametrize(
    "name,value",
    [
        ("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "0"),
        ("VIDEO_LLM_MAX_VIDEO_SIZE_MB", "-1"),
        ("VIDEO_LLM_REAL_MODEL_MAX_CONCURRENCY", "invalid"),
        ("VIDEO_LLM_REAL_MODEL_SEMAPHORE_TIMEOUT_SECONDS", "nan"),
        ("VIDEO_LLM_CHUNK_DURATION_SECONDS", "0"),
        ("VIDEO_LLM_SEGMENT_SPLIT_TIMEOUT_SECONDS", "inf"),
        ("VIDEO_LLM_MAX_DURATION_SECONDS", "-1"),
    ],
)
def test_settings_rejects_invalid_numeric_values(name, value):
    with pytest.raises(SettingsError, match=name):
        VideoLlmSettings.from_env({name: value})


@pytest.mark.parametrize(
    "name,value",
    [
        ("NVIDIA_API_BASE_URL", "relative/path"),
        ("NVIDIA_ASSET_API_BASE_URL", "ftp://assets.example.com"),
        ("NVIDIA_API_BASE_URL", "https://user:password@example.com"),
        ("NVIDIA_API_BASE_URL", "https://:443"),
        ("NVIDIA_API_BASE_URL", "https://api.example.com/v1?token=secret"),
    ],
)
def test_settings_rejects_invalid_or_credentialed_urls(name, value):
    with pytest.raises(SettingsError, match=name):
        VideoLlmSettings.from_env({name: value})


@pytest.mark.parametrize(
    "value",
    ["", " , ", "minio", "http://minio:9000", "minio:not-a-port"],
)
def test_settings_rejects_invalid_download_allowlist(value):
    with pytest.raises(SettingsError, match="VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS"):
        VideoLlmSettings.from_env({"VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS": value})


def test_settings_rejects_relative_allowed_video_path():
    with pytest.raises(SettingsError, match="VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR"):
        VideoLlmSettings.from_env(
            {"VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR": "relative/path"}
        )


def test_settings_rejects_chunk_duration_larger_than_max_duration():
    with pytest.raises(
        SettingsError,
        match="VIDEO_LLM_CHUNK_DURATION_SECONDS",
    ):
        VideoLlmSettings.from_env(
            {
                "VIDEO_LLM_CHUNK_DURATION_SECONDS": "200",
                "VIDEO_LLM_MAX_DURATION_SECONDS": "100",
            }
        )
