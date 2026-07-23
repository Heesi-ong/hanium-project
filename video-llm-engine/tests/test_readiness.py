import pytest
from fastapi.testclient import TestClient

from app.core.settings import SettingsError


def create_client(monkeypatch, tmp_path, api_key: str = "shared-secret") -> TestClient:
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", api_key)
    monkeypatch.setenv("LOG_DIR", str(tmp_path / "logs"))

    from app.main import app

    return TestClient(app)


def test_readiness_rejects_request_without_internal_api_key(monkeypatch, tmp_path):
    client = create_client(monkeypatch, tmp_path)

    response = client.get("/api/internal/readiness")

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal engine API key."


def test_readiness_rejects_request_with_wrong_internal_api_key(monkeypatch, tmp_path):
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "wrong-key"},
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal engine API key."


def test_readiness_reports_ready_in_mock_mode_with_valid_internal_api_key(monkeypatch, tmp_path):
    monkeypatch.delenv("VIDEO_LLM_ENABLED", raising=False)
    monkeypatch.delenv("VIDEO_LLM_BACKEND", raising=False)
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body == {
        "service": "video-llm-engine",
        "ready": True,
        "mode": "MOCK",
        "policy": "DISABLED",
        "installedBackend": "mock",
        "realModeRequested": False,
        "realModelReady": False,
        "reason": "Video LLM real mode is disabled; mock responses are expected.",
    }


def test_startup_rejects_real_mode_without_required_credentials(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.delenv("NVIDIA_API_KEY", raising=False)
    client = create_client(monkeypatch, tmp_path)

    with pytest.raises(SettingsError, match="NVIDIA_API_KEY is missing"):
        with client:
            pass


def test_readiness_reports_real_mode_when_video_llm_enabled_and_configured(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is True
    assert body["mode"] == "REAL"
    assert body["policy"] == "DEGRADED"
    assert body["realModeRequested"] is True
    assert body["realModelReady"] is True


def test_readiness_reports_strict_policy(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_POLICY", "STRICT")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    assert response.json()["policy"] == "STRICT"


@pytest.mark.parametrize(
    "name,value,error_pattern",
    [
        (
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
            "not-a-number",
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
        ),
        (
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
            "0",
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
        ),
        (
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
            "nan",
            "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
        ),
        (
            "VIDEO_LLM_MAX_VIDEO_SIZE_MB",
            "not-a-number",
            "VIDEO_LLM_MAX_VIDEO_SIZE_MB",
        ),
        (
            "VIDEO_LLM_MAX_VIDEO_SIZE_MB",
            "0",
            "VIDEO_LLM_MAX_VIDEO_SIZE_MB",
        ),
        ("NVIDIA_API_BASE_URL", "not-a-url", "NVIDIA_API_BASE_URL"),
        (
            "NVIDIA_ASSET_API_BASE_URL",
            "/relative/path",
            "NVIDIA_ASSET_API_BASE_URL",
        ),
    ],
)
def test_invalid_real_mode_settings_fail_before_readiness(
    monkeypatch,
    tmp_path,
    name,
    value,
    error_pattern,
):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv(name, value)
    client = create_client(monkeypatch, tmp_path)

    with pytest.raises(SettingsError, match=error_pattern):
        with client:
            pass


def test_readiness_uses_default_base_urls_when_real_mode_base_urls_are_blank(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_API_BASE_URL", "")
    monkeypatch.setenv("NVIDIA_ASSET_API_BASE_URL", "  ")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is True
    assert body["mode"] == "REAL"
    assert body["realModelReady"] is True


def test_readiness_reports_installed_backend_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "local-model")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    assert response.json()["installedBackend"] == "local-model"
