from fastapi.testclient import TestClient


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
        "installedBackend": "mock",
        "realModeRequested": False,
        "realModelReady": False,
        "reason": "Video LLM real mode is disabled; mock responses are expected.",
    }


def test_readiness_reports_fallback_when_real_mode_lacks_required_credentials(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.delenv("NVIDIA_API_KEY", raising=False)
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModeRequested"] is True
    assert body["realModelReady"] is False
    assert body["reason"] == (
        "VIDEO_LLM_ENABLED=true but NVIDIA_API_KEY is missing; "
        "analysis will fall back to mock responses."
    )


def test_readiness_reports_real_mode_when_video_llm_enabled_and_configured(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
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
    assert body["realModeRequested"] is True
    assert body["realModelReady"] is True


def test_readiness_reports_fallback_when_real_mode_timeout_is_invalid(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "not-a-number")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModeRequested"] is True
    assert body["realModelReady"] is False
    assert "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS must be a positive number" in body["reason"]


def test_readiness_reports_fallback_when_real_mode_timeout_is_not_positive(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "0")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModelReady"] is False
    assert "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS must be a positive number" in body["reason"]


def test_readiness_reports_fallback_when_real_mode_timeout_is_not_finite(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "nan")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModelReady"] is False
    assert "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS must be a positive number" in body["reason"]


def test_readiness_reports_fallback_when_real_mode_max_video_size_is_invalid(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", "not-a-number")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModelReady"] is False
    assert "VIDEO_LLM_MAX_VIDEO_SIZE_MB must be a positive integer" in body["reason"]


def test_readiness_reports_fallback_when_real_mode_max_video_size_is_not_positive(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", "0")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModelReady"] is False
    assert "VIDEO_LLM_MAX_VIDEO_SIZE_MB must be a positive integer" in body["reason"]


def test_readiness_reports_fallback_when_real_mode_api_base_url_is_invalid(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_API_BASE_URL", "not-a-url")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModelReady"] is False
    assert "NVIDIA_API_BASE_URL must be an absolute http(s) URL" in body["reason"]


def test_readiness_reports_fallback_when_real_mode_asset_base_url_is_invalid(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_ASSET_API_BASE_URL", "/relative/path")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["mode"] == "FALLBACK"
    assert body["realModelReady"] is False
    assert "NVIDIA_ASSET_API_BASE_URL must be an absolute http(s) URL" in body["reason"]


def test_readiness_uses_default_base_urls_when_real_mode_base_urls_are_blank(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
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
