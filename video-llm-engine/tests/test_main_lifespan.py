import pytest
from fastapi.testclient import TestClient

from app import main


def test_lifespan_accepts_default_video_size_limit(monkeypatch):
    monkeypatch.delenv("VIDEO_LLM_ENABLED", raising=False)
    monkeypatch.delenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", raising=False)

    with TestClient(main.app) as client:
        assert client.get("/health").status_code == 200


@pytest.mark.parametrize("configured_value", ["0", "-1", "invalid"])
def test_lifespan_rejects_invalid_video_size_limit(monkeypatch, configured_value):
    monkeypatch.delenv("VIDEO_LLM_ENABLED", raising=False)
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", configured_value)

    with pytest.raises(RuntimeError, match="must be a positive integer"):
        with TestClient(main.app):
            pass


def test_lifespan_accepts_valid_real_mode_configuration(monkeypatch):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.delenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", raising=False)
    monkeypatch.delenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", raising=False)
    monkeypatch.delenv("NVIDIA_API_BASE_URL", raising=False)
    monkeypatch.delenv("NVIDIA_ASSET_API_BASE_URL", raising=False)

    with TestClient(main.app) as client:
        assert client.get("/health").status_code == 200


def test_lifespan_rejects_real_mode_without_api_key(monkeypatch):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.delenv("NVIDIA_API_KEY", raising=False)

    with pytest.raises(RuntimeError, match="NVIDIA_API_KEY is missing"):
        with TestClient(main.app):
            pass
