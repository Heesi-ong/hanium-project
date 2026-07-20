import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.basic_analysis import router as basic_analysis_router


def create_client(monkeypatch, api_key: str = "shared-secret") -> TestClient:
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", api_key)

    app = FastAPI()
    app.include_router(basic_analysis_router)

    return TestClient(app)


@pytest.mark.parametrize(
    "malformed_job_id",
    [
        "../../etc/passwd",
        "20260720123456-deadbeef/../../secrets",
        "not-the-right-format",
        "",
        "20260720123456-DEADBEEF",  # 대문자는 backend 형식과 다름
    ],
)
def test_basic_analysis_rejects_malformed_job_id_before_touching_the_filesystem(
    monkeypatch, malformed_job_id
):
    client = create_client(monkeypatch)

    response = client.post(
        "/api/basic-analysis",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json={
            "jobId": malformed_job_id,
            "videoPath": "/storage/uploads/some-job/original.mp4",
        },
    )

    assert response.status_code == 422


def test_basic_analysis_accepts_the_backend_job_id_format(monkeypatch):
    client = create_client(monkeypatch)

    response = client.post(
        "/api/basic-analysis",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json={
            "jobId": "20260720123456-deadbeef",
            "videoPath": "/storage/uploads/20260720123456-deadbeef/original.mp4",
        },
    )

    # 형식 검증은 통과해야 하므로 422가 아니어야 합니다. 실제 파이프라인은 비디오
    # 파일이 없어 이후 단계에서 실패하지만, 그건 이 테스트의 관심사가 아닙니다.
    assert response.status_code != 422
