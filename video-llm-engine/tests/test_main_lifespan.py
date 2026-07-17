import pytest
from fastapi.testclient import TestClient

from app import main


def test_lifespan_accepts_default_video_size_limit(monkeypatch):
    monkeypatch.delenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", raising=False)

    with TestClient(main.app) as client:
        assert client.get("/health").status_code == 200


@pytest.mark.parametrize("configured_value", ["0", "-1", "invalid"])
def test_lifespan_rejects_invalid_video_size_limit(monkeypatch, configured_value):
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", configured_value)

    with pytest.raises(RuntimeError, match="must be a positive integer"):
        with TestClient(main.app):
            pass
