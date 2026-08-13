import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", "validation-secret")
    monkeypatch.setenv("VIDEO_LLM_POLICY", "DISABLED")
    return TestClient(app)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("jobId", "../unsafe/job"),
        ("jobId", "job-with-newline\nforged-log"),
        ("sampleFps", 0),
        ("sampleFps", 31),
        ("maxFrames", 0),
        ("maxFrames", 601),
        ("durationSec", 0),
        ("durationSec", -1),
    ],
)
def test_analyze_rejects_unsafe_or_out_of_range_request_fields(client, field, value):
    payload = {
        "jobId": "safe-job-1",
        "videoPath": "/storage/uploads/safe-job-1/original.mp4",
        "sampleFps": 1,
        "maxFrames": 90,
    }
    payload[field] = value

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "validation-secret"},
        json=payload,
    )

    assert response.status_code == 422


def test_analyze_accepts_safe_backend_job_id(client):
    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "validation-secret"},
        json={
            "jobId": "20260813080856-6975cbf1",
            "videoPath": "/storage/uploads/20260813080856-6975cbf1/original.mp4",
            "sampleFps": 1,
            "maxFrames": 90,
        },
    )

    assert response.status_code == 200
    assert response.json()["jobId"] == "20260813080856-6975cbf1"

