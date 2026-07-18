from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.readiness import router as readiness_router
from app.core import model_registry


def create_client(monkeypatch, api_key: str = "shared-secret") -> TestClient:
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", api_key)

    app = FastAPI()
    app.include_router(readiness_router)

    return TestClient(app)


def test_readiness_rejects_request_without_internal_api_key(monkeypatch):
    client = create_client(monkeypatch)

    response = client.get("/api/internal/readiness")

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal engine API key."


def test_readiness_rejects_request_with_wrong_internal_api_key(monkeypatch):
    client = create_client(monkeypatch)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "wrong-key"},
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal engine API key."


def test_readiness_reports_not_ready_when_models_not_loaded(monkeypatch):
    client = create_client(monkeypatch)

    monkeypatch.setattr(model_registry, "_whisper_loaded_count", 0)
    monkeypatch.setattr(model_registry, "_pose_loaded_count", 0)
    monkeypatch.setattr(model_registry, "_face_loaded_count", 0)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["service"] == "analysis-engine"
    assert body["ready"] is False
    assert body["models"] == {"whisper": False, "pose": False, "face": False}
    assert body["reason"] == "Analysis models are not loaded: whisper, pose, face"


def test_readiness_reports_ready_when_models_loaded(monkeypatch):
    client = create_client(monkeypatch)

    monkeypatch.setattr(model_registry, "_whisper_loaded_count", 1)
    monkeypatch.setattr(model_registry, "_pose_loaded_count", 1)
    monkeypatch.setattr(model_registry, "_face_loaded_count", 1)

    response = client.get(
        "/api/internal/readiness",
        headers={"X-Internal-Api-Key": "shared-secret"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is True
    assert body["models"] == {"whisper": True, "pose": True, "face": True}
    assert body["reason"] == "All analysis models are loaded."
