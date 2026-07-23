import pytest
from fastapi.testclient import TestClient

from app import main


def test_lifespan_preloads_and_closes_model_pool(monkeypatch):
    events = []

    monkeypatch.setattr(main.model_registry, "preload_all", lambda: events.append("preload"))
    monkeypatch.setattr(main.model_registry, "close_all", lambda: events.append("close"))

    with TestClient(main.app) as client:
        assert events == ["preload"]
        assert client.get("/health").status_code == 200

    assert events == ["preload", "close"]


def test_lifespan_closes_partial_pool_when_preload_fails(monkeypatch):
    events = []

    def fail_preload():
        events.append("preload")
        raise RuntimeError("model preload failed")

    monkeypatch.setattr(main.model_registry, "preload_all", fail_preload)
    monkeypatch.setattr(main.model_registry, "close_all", lambda: events.append("close"))

    with pytest.raises(RuntimeError, match="model preload failed"):
        with TestClient(main.app):
            pass

    assert events == ["preload", "close"]


def test_lifespan_rejects_invalid_video_size_before_model_preload(monkeypatch):
    events = []
    monkeypatch.setenv("ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB", "0")
    monkeypatch.setattr(main.model_registry, "preload_all", lambda: events.append("preload"))
    monkeypatch.setattr(main.model_registry, "close_all", lambda: events.append("close"))

    with pytest.raises(RuntimeError, match="must be a positive integer"):
        with TestClient(main.app):
            pass

    assert events == ["close"]


def test_lifespan_rejects_invalid_whisper_timeout_before_model_preload(monkeypatch):
    events = []
    monkeypatch.setenv("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", "0")
    monkeypatch.setattr(main.model_registry, "preload_all", lambda: events.append("preload"))
    monkeypatch.setattr(main.model_registry, "close_all", lambda: events.append("close"))

    with pytest.raises(ValueError, match="ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS"):
        with TestClient(main.app):
            pass

    assert events == ["close"]


def test_lifespan_rejects_empty_allowed_download_hosts_before_model_preload(monkeypatch):
    events = []
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "")
    monkeypatch.setattr(main.model_registry, "preload_all", lambda: events.append("preload"))
    monkeypatch.setattr(main.model_registry, "close_all", lambda: events.append("close"))

    with pytest.raises(RuntimeError, match="ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS"):
        with TestClient(main.app):
            pass

    assert events == ["close"]
