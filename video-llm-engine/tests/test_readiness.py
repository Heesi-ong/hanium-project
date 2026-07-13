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
    }


def test_readiness_reports_real_mode_when_video_llm_enabled(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    assert response.json()["mode"] == "REAL"


def test_readiness_reports_installed_backend_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "local-model")
    client = create_client(monkeypatch, tmp_path)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    assert response.json()["installedBackend"] == "local-model"
