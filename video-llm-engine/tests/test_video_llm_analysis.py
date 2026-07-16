import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import video_llm_analysis
from app.api.video_llm_analysis import VideoLlmAnalysisRequest


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


def create_video_file(tmp_path) -> str:
    video_path = tmp_path / "sample.mp4"
    video_path.write_bytes(b"fake mp4 bytes")
    return str(video_path)


def nvidia_model_payload() -> dict:
    item = {
        "startSec": 1,
        "endSec": 3,
        "label": "stable",
        "description": "발표자가 안정적으로 보입니다.",
        "confidence": 0.82,
    }
    return {
        "observations": {
            "eyeContact": [item],
            "facialExpression": [item],
            "gesture": [item],
            "posture": [item],
        },
        "globalSummary": {
            "visualDelivery": "전반적으로 안정적인 발표 태도입니다.",
            "mainStrength": "자세가 안정적입니다.",
            "mainWeakness": "제스처 다양성은 더 보완할 수 있습니다.",
        },
    }


class FakeNvidiaClient:
    instances = []
    response_content = ""
    response_status = 200
    post_json = None
    get_responses = []
    asset_create_status = 200
    asset_create_json = {
        "assetId": "asset-123",
        "uploadUrl": "https://example.com/upload/asset-123",
        "contentType": "video/mp4",
        "description": "test asset",
    }
    upload_status = 200
    delete_status = 204
    raised_exception = None

    def __init__(self, timeout):
        self.timeout = timeout
        self.requests = []
        FakeNvidiaClient.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def post(self, url, headers, json):
        self.requests.append({
            "method": "POST",
            "url": url,
            "headers": headers,
            "json": json,
        })
        if url.endswith("/assets"):
            return httpx.Response(
                FakeNvidiaClient.asset_create_status,
                json=FakeNvidiaClient.asset_create_json,
                request=httpx.Request("POST", url),
            )
        if FakeNvidiaClient.raised_exception:
            raise FakeNvidiaClient.raised_exception
        if FakeNvidiaClient.post_json is not None:
            return httpx.Response(
                FakeNvidiaClient.response_status,
                json=FakeNvidiaClient.post_json,
                request=httpx.Request("POST", url),
            )
        return httpx.Response(
            FakeNvidiaClient.response_status,
            json={
                "choices": [
                    {
                        "message": {
                            "content": FakeNvidiaClient.response_content,
                        },
                    },
                ],
            },
            request=httpx.Request("POST", url),
        )

    def put(self, url, headers, content):
        self.requests.append({
            "method": "PUT",
            "url": url,
            "headers": headers,
            "content": content,
        })
        return httpx.Response(
            FakeNvidiaClient.upload_status,
            request=httpx.Request("PUT", url),
        )

    def get(self, url, headers):
        self.requests.append({
            "method": "GET",
            "url": url,
            "headers": headers,
        })
        if not FakeNvidiaClient.get_responses:
            raise AssertionError("Unexpected NVIDIA polling request.")
        response = FakeNvidiaClient.get_responses.pop(0)
        return httpx.Response(
            response["status"],
            json=response.get("json", {}),
            request=httpx.Request("GET", url),
        )

    def delete(self, url, headers):
        self.requests.append({
            "method": "DELETE",
            "url": url,
            "headers": headers,
        })
        return httpx.Response(
            FakeNvidiaClient.delete_status,
            request=httpx.Request("DELETE", url),
        )


def install_fake_nvidia_client(monkeypatch, response_content):
    FakeNvidiaClient.instances = []
    FakeNvidiaClient.response_content = response_content
    FakeNvidiaClient.response_status = 200
    FakeNvidiaClient.post_json = None
    FakeNvidiaClient.get_responses = []
    FakeNvidiaClient.asset_create_status = 200
    FakeNvidiaClient.asset_create_json = {
        "assetId": "asset-123",
        "uploadUrl": "https://example.com/upload/asset-123",
        "contentType": "video/mp4",
        "description": "test asset",
    }
    FakeNvidiaClient.upload_status = 200
    FakeNvidiaClient.delete_status = 204
    FakeNvidiaClient.raised_exception = None
    monkeypatch.setattr(video_llm_analysis.httpx, "Client", FakeNvidiaClient)


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
        "generationMode": "MOCK",
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


def test_analyze_uses_mock_generation_mode_when_video_llm_disabled(monkeypatch, tmp_path):
    monkeypatch.delenv("VIDEO_LLM_ENABLED", raising=False)
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("mock-mode-job"),
    )

    assert response.status_code == 200
    assert response.json()["model"]["generationMode"] == "MOCK"


def test_analyze_falls_back_to_mock_when_real_video_llm_is_enabled_but_not_configured(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("fallback-mode-job"),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["jobId"] == "fallback-mode-job"
    assert body["status"] == "success"
    assert body["model"]["generationMode"] == "FALLBACK"


def test_call_real_video_llm_model_normalizes_nvidia_response(monkeypatch, tmp_path):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv(
        "NVIDIA_VIDEO_LLM_MODEL",
        "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
    )
    monkeypatch.setenv("NVIDIA_API_BASE_URL", "https://integrate.api.nvidia.com/v1")
    monkeypatch.setenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "45")

    response = video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="real-job",
            videoPath=video_path,
            sampleFps=1,
            maxFrames=90,
        )
    )

    assert response["jobId"] == "real-job"
    assert response["status"] == "success"
    assert response["model"] == {
        "name": "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
        "version": "nvidia-nim",
        "generationMode": "REAL",
    }
    assert set(response["observations"].keys()) == {
        "eyeContact",
        "facialExpression",
        "gesture",
        "posture",
    }
    assert response["observations"]["eyeContact"][0]["confidence"] == 0.82
    assert response["globalSummary"]["mainStrength"] == "자세가 안정적입니다."

    client = FakeNvidiaClient.instances[0]
    assert client.timeout == 45.0
    sent_request = client.requests[0]
    assert sent_request["method"] == "POST"
    assert sent_request["url"] == "https://integrate.api.nvidia.com/v1/chat/completions"
    assert sent_request["headers"]["Authorization"] == "Bearer nvapi-test-key"
    assert sent_request["json"]["model"] == "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
    assert sent_request["json"]["messages"][0]["content"].startswith("/no_think")
    user_content = sent_request["json"]["messages"][1]["content"]
    assert user_content[1]["type"] == "video_url"
    assert user_content[1]["video_url"]["url"].startswith("data:video/mp4;base64,")


def test_call_real_video_llm_model_includes_duration_hint_in_prompt(monkeypatch, tmp_path):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="duration-prompt-job",
            videoPath=video_path,
            durationSec=4.166,
        )
    )

    sent_request = FakeNvidiaClient.instances[0].requests[0]
    user_text = sent_request["json"]["messages"][1]["content"][0]["text"]
    assert "The video is exactly 4.166 seconds long." in user_text
    assert "within [0, 4.166]" in user_text
    assert "must not all be 0" in user_text
    assert "Divide the video into three temporal segments" in user_text
    assert "[0, 1.389)" in user_text
    assert "[1.389, 2.777)" in user_text
    assert "[2.777, 4.166]" in user_text
    assert "include at least one observation for each segment" in user_text
    assert "do not collapse all observations into a single [0, duration] range" in user_text


def test_build_duration_prompt_without_duration_keeps_existing_empty_prompt():
    assert video_llm_analysis.build_duration_prompt(None) == ""


def test_normalize_video_llm_response_clamps_times_to_duration():
    item = {
        "startSec": 4.8,
        "endSec": 5.2,
        "label": "late",
        "description": "시간이 길이를 넘어갑니다.",
        "confidence": 0.7,
    }
    payload = {
        "observations": {
            "eyeContact": [item],
            "facialExpression": [item],
            "gesture": [item],
            "posture": [item],
        },
        "globalSummary": {
            "visualDelivery": "요약",
            "mainStrength": "강점",
            "mainWeakness": "약점",
        },
    }

    response = video_llm_analysis.normalize_video_llm_response(
        "clamp-job",
        "test-model",
        payload,
        duration_sec=4.166,
    )

    assert response["observations"]["eyeContact"][0]["startSec"] == 4.166
    assert response["observations"]["eyeContact"][0]["endSec"] == 4.166


def test_normalize_video_llm_response_keeps_existing_validation_without_duration():
    item = {
        "startSec": 5,
        "endSec": 4,
        "label": "invalid",
        "description": "끝 시간이 시작보다 빠릅니다.",
        "confidence": 0.7,
    }
    payload = {
        "observations": {
            "eyeContact": [item],
            "facialExpression": [item],
            "gesture": [item],
            "posture": [item],
        },
        "globalSummary": {
            "visualDelivery": "요약",
            "mainStrength": "강점",
            "mainWeakness": "약점",
        },
    }

    with pytest.raises(ValueError, match="endSec < startSec"):
        video_llm_analysis.normalize_video_llm_response(
            "no-duration-job",
            "test-model",
            payload,
        )


def test_call_real_video_llm_model_uses_nvcf_asset_for_large_video(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (video_llm_analysis.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
    )
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    response = video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="large-video-job",
            videoPath=str(video_path),
        )
    )

    assert response["jobId"] == "large-video-job"
    main_client = FakeNvidiaClient.instances[0]
    assert [request["method"] for request in main_client.requests] == [
        "POST",
        "PUT",
        "POST",
    ]
    asset_create_request = main_client.requests[0]
    assert asset_create_request["url"] == "https://api.nvcf.nvidia.com/v2/nvcf/assets"
    assert asset_create_request["json"]["contentType"] == "video/mp4"
    upload_request = main_client.requests[1]
    assert upload_request["headers"]["x-amz-meta-nvcf-asset-description"] == (
        "video-llm-analysis jobId=large-video-job"
    )
    chat_request = main_client.requests[2]
    assert chat_request["headers"]["NVCF-INPUT-ASSET-REFERENCES"] == "asset-123"
    assert '<video src="data:video/mp4;asset_id,asset-123" />' in (
        chat_request["json"]["messages"][0]["content"]
    )

    cleanup_client = FakeNvidiaClient.instances[1]
    assert cleanup_client.requests == [
        {
            "method": "DELETE",
            "url": "https://api.nvcf.nvidia.com/v2/nvcf/assets/asset-123",
            "headers": {"Authorization": "Bearer nvapi-test-key"},
        }
    ]


def test_call_real_video_llm_model_raises_when_nvcf_asset_create_fails(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (video_llm_analysis.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
    )
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.asset_create_status = 500
    FakeNvidiaClient.asset_create_json = {"error": "asset create failed"}
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    with pytest.raises(httpx.HTTPStatusError):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="asset-create-failure-job",
                videoPath=str(video_path),
            )
        )


def test_call_real_video_llm_model_deletes_asset_when_upload_fails(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (video_llm_analysis.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
    )
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.upload_status = 403
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    with pytest.raises(httpx.HTTPStatusError):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="asset-upload-failure-job",
                videoPath=str(video_path),
            )
        )

    main_client = FakeNvidiaClient.instances[0]
    assert [request["method"] for request in main_client.requests] == [
        "POST",
        "PUT",
        "DELETE",
    ]


def test_call_real_video_llm_model_ignores_nvcf_asset_delete_failure(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (video_llm_analysis.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
    )
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.delete_status = 500
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    response = video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="asset-delete-failure-job",
            videoPath=str(video_path),
        )
    )

    assert response["jobId"] == "asset-delete-failure-job"
    cleanup_client = FakeNvidiaClient.instances[1]
    assert cleanup_client.requests[0]["method"] == "DELETE"


def test_call_real_video_llm_model_polls_accepted_nvidia_response(
    monkeypatch,
    tmp_path,
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, "")
    FakeNvidiaClient.response_status = 202
    FakeNvidiaClient.post_json = {"requestId": "11111111-1111-1111-1111-111111111111"}
    FakeNvidiaClient.get_responses = [
        {
            "status": 202,
            "json": {"requestId": "11111111-1111-1111-1111-111111111111"},
        },
        {
            "status": 200,
            "json": {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(nvidia_model_payload()),
                        },
                    },
                ],
            },
        },
    ]
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setattr(video_llm_analysis.time, "sleep", lambda _: None)

    response = video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="polling-job",
            videoPath=video_path,
        )
    )

    assert response["jobId"] == "polling-job"
    assert response["model"]["name"] == video_llm_analysis.NVIDIA_DEFAULT_MODEL
    client = FakeNvidiaClient.instances[0]
    assert [request["method"] for request in client.requests] == ["POST", "GET", "GET"]
    assert client.requests[1]["url"] == (
        "https://integrate.api.nvidia.com/v1/status/"
        "11111111-1111-1111-1111-111111111111"
    )


def test_call_real_video_llm_model_raises_when_nvidia_polling_times_out(
    monkeypatch,
    tmp_path,
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, "")
    FakeNvidiaClient.response_status = 202
    FakeNvidiaClient.post_json = {"requestId": "22222222-2222-2222-2222-222222222222"}
    FakeNvidiaClient.get_responses = [
        {
            "status": 202,
            "json": {"requestId": "22222222-2222-2222-2222-222222222222"},
        }
        for _ in range(video_llm_analysis.NVIDIA_STATUS_POLL_MAX_ATTEMPTS)
    ]
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setattr(video_llm_analysis.time, "sleep", lambda _: None)

    with pytest.raises(TimeoutError, match="did not finish"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="polling-timeout-job",
                videoPath=video_path,
            )
        )


def test_analyze_falls_back_when_nvidia_response_is_missing_required_fields(
    monkeypatch,
    tmp_path,
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(
        monkeypatch,
        json.dumps({
            "observations": {},
            "globalSummary": {
                "visualDelivery": "요약",
                "mainStrength": "강점",
                "mainWeakness": "약점",
            },
        }),
    )
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    client = create_client(monkeypatch, tmp_path)
    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json={
            **analysis_payload("fallback-bad-schema-job"),
            "videoPath": video_path,
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["jobId"] == "fallback-bad-schema-job"
    assert body["model"]["generationMode"] == "FALLBACK"


def test_call_real_video_llm_model_raises_when_nvidia_api_key_is_missing(
    monkeypatch,
    tmp_path,
):
    monkeypatch.delenv("NVIDIA_API_KEY", raising=False)

    with pytest.raises(RuntimeError, match="NVIDIA_API_KEY"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="missing-key-job",
                videoPath=create_video_file(tmp_path),
            )
        )


def test_call_real_video_llm_model_raises_on_nvidia_timeout(monkeypatch, tmp_path):
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.raised_exception = httpx.TimeoutException("timed out")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    with pytest.raises(httpx.TimeoutException):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="timeout-job",
                videoPath=create_video_file(tmp_path),
            )
        )


class FakeDownloadClient:
    response_status = 200
    response_content = b""
    response_headers = None
    raised_exception = None

    def __init__(self, timeout):
        self.timeout = timeout

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def get(self, url):
        if FakeDownloadClient.raised_exception:
            raise FakeDownloadClient.raised_exception

        return httpx.Response(
            FakeDownloadClient.response_status,
            content=FakeDownloadClient.response_content,
            headers=FakeDownloadClient.response_headers or {"content-type": "video/mp4"},
            request=httpx.Request("GET", url),
        )


def test_resolve_video_bytes_downloads_when_video_download_url_present(monkeypatch, tmp_path):
    FakeDownloadClient.response_status = 200
    FakeDownloadClient.response_content = b"downloaded-bytes"
    FakeDownloadClient.response_headers = {"content-type": "video/mp4"}
    FakeDownloadClient.raised_exception = None
    monkeypatch.setattr(video_llm_analysis.httpx, "Client", FakeDownloadClient)

    request = VideoLlmAnalysisRequest(
        jobId="video-llm-download-1",
        videoPath=create_video_file(tmp_path),
        videoDownloadUrl="https://minio.local/uploads/video-llm-download-1/original.mp4",
    )

    video_bytes, content_type = video_llm_analysis.resolve_video_bytes(request)

    assert video_bytes == b"downloaded-bytes"
    assert content_type == "video/mp4"


def test_resolve_video_bytes_falls_back_to_local_path_when_download_fails(monkeypatch, tmp_path):
    FakeDownloadClient.raised_exception = httpx.ConnectError("connection failed")
    monkeypatch.setattr(video_llm_analysis.httpx, "Client", FakeDownloadClient)

    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-download-2",
        videoPath=video_path,
        videoDownloadUrl="https://minio.local/uploads/video-llm-download-2/original.mp4",
    )

    video_bytes, content_type = video_llm_analysis.resolve_video_bytes(request)

    assert video_bytes == b"fake mp4 bytes"
    assert content_type == "video/mp4"


def test_resolve_video_bytes_uses_local_path_when_no_download_url(tmp_path):
    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-no-url",
        videoPath=video_path,
    )

    video_bytes, content_type = video_llm_analysis.resolve_video_bytes(request)

    assert video_bytes == b"fake mp4 bytes"
    assert content_type == "video/mp4"
