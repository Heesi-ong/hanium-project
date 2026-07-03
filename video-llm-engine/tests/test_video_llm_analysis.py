from fastapi.testclient import TestClient


def create_client(monkeypatch, tmp_path, api_key: str = "shared-secret") -> TestClient:
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", api_key)
    monkeypatch.setenv("LOG_DIR", str(tmp_path / "logs"))

    from app.main import app

    return TestClient(app)


def analysis_payload(job_id: str = "video-llm-job-1") -> dict:
    return {
        "jobId": job_id,
        "videoPath": "/storage/uploads/video-llm-job-1/original.mp4",
        "sampleFps": 1,
        "maxFrames": 90,
    }


def test_analyze_rejects_request_without_internal_api_key(monkeypatch, tmp_path):
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        json=analysis_payload(),
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal engine API key."


def test_analyze_rejects_request_with_wrong_internal_api_key(monkeypatch, tmp_path):
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "wrong-key"},
        json=analysis_payload(),
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal engine API key."


def test_analyze_returns_mock_video_llm_result_with_valid_internal_api_key(monkeypatch, tmp_path):
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("job-abc"),
    )

    assert response.status_code == 200

    body = response.json()
    assert body["jobId"] == "job-abc"
    assert body["status"] == "success"
    assert body["model"] == {
        "name": "mock-video-llm",
        "version": "local-mock",
    }
    assert set(body["observations"].keys()) == {
        "eyeContact",
        "facialExpression",
        "gesture",
        "posture",
    }
    assert "visualDelivery" in body["globalSummary"]
    assert "mainStrength" in body["globalSummary"]
    assert "mainWeakness" in body["globalSummary"]
