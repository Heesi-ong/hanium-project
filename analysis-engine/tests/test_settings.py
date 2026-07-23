from pathlib import Path

import pytest

from app.core.settings import AnalysisEngineSettings, SettingsError


def test_settings_loads_and_normalizes_all_engine_environment_values(tmp_path):
    settings = AnalysisEngineSettings.from_env(
        {
            "INTERNAL_ENGINE_API_KEY": " shared-secret ",
            "LOG_DIR": str(tmp_path / "logs"),
            "ANALYSIS_ENGINE_WHISPER_POOL_SIZE": "3",
            "ANALYSIS_ENGINE_POSE_POOL_SIZE": "4",
            "ANALYSIS_ENGINE_FACE_POOL_SIZE": "5",
            "ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB": "600",
            "ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS": "90.5",
            "ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS": "MinIO:9000, media:9443",
            "ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR": str(tmp_path),
        }
    )

    assert settings.internal_engine_api_key == "shared-secret"
    assert settings.log_dir == (tmp_path / "logs").resolve()
    assert settings.whisper_pool_size == 3
    assert settings.pose_pool_size == 4
    assert settings.face_pool_size == 5
    assert settings.max_video_size_bytes == 600 * 1024 * 1024
    assert settings.whisper_transcribe_timeout_seconds == 90.5
    assert settings.allowed_download_hosts == frozenset(
        {"minio:9000", "media:9443"}
    )
    assert settings.allowed_video_base_dir == tmp_path.resolve()


@pytest.mark.parametrize(
    "name,value",
    [
        ("ANALYSIS_ENGINE_WHISPER_POOL_SIZE", "0"),
        ("ANALYSIS_ENGINE_POSE_POOL_SIZE", "-1"),
        ("ANALYSIS_ENGINE_FACE_POOL_SIZE", "invalid"),
        ("ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB", "0"),
        ("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", "nan"),
        ("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", "0"),
    ],
)
def test_settings_rejects_invalid_numeric_values(name, value):
    with pytest.raises(SettingsError, match=name):
        AnalysisEngineSettings.from_env({name: value})


@pytest.mark.parametrize(
    "value",
    ["", " , ", "minio", "http://minio:9000", "minio:not-a-port"],
)
def test_settings_rejects_invalid_download_allowlist(value):
    with pytest.raises(SettingsError, match="ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS"):
        AnalysisEngineSettings.from_env(
            {"ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS": value}
        )


def test_settings_rejects_relative_allowed_video_path():
    with pytest.raises(SettingsError, match="ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR"):
        AnalysisEngineSettings.from_env(
            {"ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR": "relative/path"}
        )


def test_settings_normalizes_relative_log_path_to_absolute_path():
    settings = AnalysisEngineSettings.from_env({"LOG_DIR": "../storage/logs"})

    assert settings.log_dir.is_absolute()


def test_settings_defaults_are_valid_and_use_absolute_paths():
    settings = AnalysisEngineSettings.from_env({})

    assert settings.max_video_size_bytes == 500 * 1024 * 1024
    assert settings.log_dir.is_absolute()
    assert settings.log_dir.name == "logs"
    assert settings.allowed_video_base_dir == Path("/storage")
