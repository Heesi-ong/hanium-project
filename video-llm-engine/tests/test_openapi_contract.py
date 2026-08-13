from fastapi.testclient import TestClient

from app.main import app


def test_video_llm_openapi_exposes_typed_request_and_response(monkeypatch):
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", "contract-secret")
    schema = TestClient(app).get("/openapi.json").json()
    operation = schema["paths"]["/api/video-llm/analyze"]["post"]

    request_schema = operation["requestBody"]["content"]["application/json"]["schema"]
    response_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]

    assert request_schema["$ref"].endswith("/VideoLlmAnalysisRequest")
    assert response_schema["$ref"].endswith("/VideoLlmAnalysisResponse")

    request_contract = schema["components"]["schemas"]["VideoLlmAnalysisRequest"]
    assert request_contract["properties"]["sampleFps"]["minimum"] == 1
    assert request_contract["properties"]["maxFrames"]["maximum"] == 600

    model_contract = schema["components"]["schemas"]["VideoModelInfo"]
    assert set(model_contract["properties"]["generationMode"]["enum"]) == {
        "REAL",
        "MOCK",
        "FALLBACK",
    }

