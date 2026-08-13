from fastapi.testclient import TestClient

from app.main import app


def test_basic_analysis_openapi_exposes_typed_request_and_response(monkeypatch):
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", "contract-secret")
    schema = TestClient(app).get("/openapi.json").json()
    operation = schema["paths"]["/api/basic-analysis"]["post"]

    request_schema = operation["requestBody"]["content"]["application/json"]["schema"]
    response_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]

    assert request_schema["$ref"].endswith("/BasicAnalysisRequest")
    assert response_schema["$ref"].endswith("/BasicAnalysisResponse")

    response_contract = schema["components"]["schemas"]["BasicAnalysisResponse"]
    assert set(response_contract["required"]) >= {
        "jobId",
        "status",
        "videoInfo",
        "frame",
        "score",
    }

    score_contract = schema["components"]["schemas"]["ScoreResponse"]
    total_score = score_contract["properties"]["totalScore"]
    assert total_score["minimum"] == 0
    assert total_score["maximum"] == 100

