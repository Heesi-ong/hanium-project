import json
import math
from pathlib import Path
import subprocess
import tempfile
import threading

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import video_llm_analysis
from app.api.video_llm_analysis import VideoLlmAnalysisRequest
from app.services import (
    media_io,
    nvidia_provider,
    nvidia_response,
    nvidia_runtime,
    video_pipeline,
)


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


def create_real_video_file(
    tmp_path, duration_seconds: float, name: str = "real.mp4"
) -> str:
    """구간 분할(ffmpeg) 테스트는 실제로 디코딩 가능한 영상이 있어야 하므로, lavfi로
    지정한 길이만큼의 최소 동영상을 실제로 만들어 반환합니다."""
    video_path = tmp_path / name
    ffmpeg_executable = video_pipeline.imageio_ffmpeg.get_ffmpeg_exe()
    subprocess.run(
        [
            ffmpeg_executable,
            "-y",
            "-f",
            "lavfi",
            "-i",
            f"color=c=blue:s=64x64:d={duration_seconds}",
            "-f",
            "lavfi",
            "-i",
            f"sine=frequency=1000:duration={duration_seconds}",
            "-c:v",
            "libx264",
            "-c:a",
            "aac",
            "-shortest",
            str(video_path),
        ],
        check=True,
        capture_output=True,
        timeout=30,
    )
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

    def post(self, url, headers, json, timeout=None):
        self.requests.append(
            {
                "method": "POST",
                "url": url,
                "headers": headers,
                "json": json,
            }
        )
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

    def put(self, url, headers, content, timeout=None):
        uploaded_content = (
            bytes(content)
            if isinstance(content, (bytes, bytearray))
            else b"".join(content)
        )
        self.requests.append(
            {
                "method": "PUT",
                "url": url,
                "headers": headers,
                "content": uploaded_content,
            }
        )
        return httpx.Response(
            FakeNvidiaClient.upload_status,
            request=httpx.Request("PUT", url),
        )

    def get(self, url, headers, timeout=None):
        self.requests.append(
            {
                "method": "GET",
                "url": url,
                "headers": headers,
            }
        )
        if not FakeNvidiaClient.get_responses:
            raise AssertionError("Unexpected NVIDIA polling request.")
        response = FakeNvidiaClient.get_responses.pop(0)
        return httpx.Response(
            response["status"],
            json=response.get("json", {}),
            request=httpx.Request("GET", url),
        )

    def delete(self, url, headers, timeout=None):
        self.requests.append(
            {
                "method": "DELETE",
                "url": url,
                "headers": headers,
            }
        )
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
    monkeypatch.setattr(nvidia_provider.httpx, "Client", FakeNvidiaClient)


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


def test_analyze_returns_mock_video_llm_result_with_valid_internal_api_key(
    monkeypatch, tmp_path
):
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


def test_analyze_binds_job_id_and_request_id_for_the_duration_of_the_request(
    monkeypatch, tmp_path
):
    from app.core.logging_config import job_id_var, request_id_var

    client = create_client(monkeypatch, tmp_path)
    captured = {}
    original_build_mock_response = video_llm_analysis.build_mock_response

    def spy_build_mock_response(request, mode):
        captured["job_id"] = job_id_var.get()
        captured["request_id"] = request_id_var.get()
        return original_build_mock_response(request, mode)

    monkeypatch.setattr(
        video_llm_analysis, "build_mock_response", spy_build_mock_response
    )

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret", "X-Request-Id": "req-corr-1"},
        json=analysis_payload("job-corr-1"),
    )

    assert response.status_code == 200
    assert captured == {"job_id": "job-corr-1", "request_id": "req-corr-1"}
    # 요청이 끝난 뒤에는 다음 요청 로그에 새지 않도록 기본값으로 되돌아가야 합니다.
    assert job_id_var.get() == "-"
    assert request_id_var.get() == "-"


def test_analyze_defaults_request_id_to_dash_when_header_is_absent(
    monkeypatch, tmp_path
):
    from app.core.logging_config import request_id_var

    client = create_client(monkeypatch, tmp_path)
    captured = {}
    original_build_mock_response = video_llm_analysis.build_mock_response

    def spy_build_mock_response(request, mode):
        captured["request_id"] = request_id_var.get()
        return original_build_mock_response(request, mode)

    monkeypatch.setattr(
        video_llm_analysis, "build_mock_response", spy_build_mock_response
    )

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("job-no-request-id"),
    )

    assert response.status_code == 200
    assert captured["request_id"] == "-"


def test_analyze_uses_mock_generation_mode_when_video_llm_disabled(
    monkeypatch, tmp_path
):
    monkeypatch.delenv("VIDEO_LLM_ENABLED", raising=False)
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("mock-mode-job"),
    )

    assert response.status_code == 200
    assert response.json()["model"]["generationMode"] == "MOCK"


def test_analyze_returns_502_when_real_model_fails_under_strict_policy(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("VIDEO_LLM_POLICY", "STRICT")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    def fail_real_model(_request):
        raise RuntimeError("vendor unavailable")

    monkeypatch.setattr(
        video_llm_analysis,
        "call_real_video_llm_model",
        fail_real_model,
    )
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("strict-failure-job"),
    )

    assert response.status_code == 502
    assert response.json() == {
        "detail": {
            "code": "VIDEO_LLM_REAL_MODEL_FAILED",
            "message": "실제 Video LLM 분석에 실패했습니다.",
        }
    }


def test_analyze_returns_fallback_when_real_model_fails_under_degraded_policy(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("VIDEO_LLM_POLICY", "DEGRADED")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    def fail_real_model(_request):
        raise RuntimeError("vendor unavailable")

    monkeypatch.setattr(
        video_llm_analysis,
        "call_real_video_llm_model",
        fail_real_model,
    )
    client = create_client(monkeypatch, tmp_path)

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=analysis_payload("degraded-failure-job"),
    )

    assert response.status_code == 200
    assert response.json()["model"]["generationMode"] == "FALLBACK"


def test_analyze_require_real_rejects_fallback_under_degraded_policy(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("VIDEO_LLM_POLICY", "DEGRADED")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    def fail_real_model(_request):
        raise RuntimeError("vendor unavailable")

    monkeypatch.setattr(
        video_llm_analysis,
        "call_real_video_llm_model",
        fail_real_model,
    )
    client = create_client(monkeypatch, tmp_path)
    payload = {**analysis_payload("require-real-degraded-job"), "requireReal": True}

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=payload,
    )

    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "VIDEO_LLM_REAL_MODEL_FAILED"


def test_analyze_require_real_rejects_mock_when_policy_is_disabled(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("VIDEO_LLM_POLICY", "DISABLED")
    client = create_client(monkeypatch, tmp_path)
    payload = {**analysis_payload("require-real-disabled-job"), "requireReal": True}

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=payload,
    )

    assert response.status_code == 503
    assert response.json() == {
        "detail": {
            "code": "VIDEO_LLM_REAL_MODEL_DISABLED",
            "message": "현재 실제 Video LLM 분석을 사용할 수 없습니다.",
        }
    }


def test_startup_rejects_real_video_llm_when_api_key_is_not_configured(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.delenv("NVIDIA_API_KEY", raising=False)
    client = create_client(monkeypatch, tmp_path)

    with pytest.raises(RuntimeError, match="NVIDIA_API_KEY is missing"):
        with client:
            pass


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
    assert (
        sent_request["json"]["model"] == "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
    )
    assert sent_request["json"]["messages"][0]["content"].startswith("/no_think")
    user_content = sent_request["json"]["messages"][1]["content"]
    assert user_content[1]["type"] == "video_url"
    assert user_content[1]["video_url"]["url"].startswith("data:video/mp4;base64,")


def test_call_real_video_llm_model_raises_when_concurrency_semaphore_is_exhausted(
    monkeypatch, tmp_path
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_REAL_MODEL_SEMAPHORE_TIMEOUT_SECONDS", "0.05")

    exhausted_semaphore = threading.Semaphore(1)
    exhausted_semaphore.acquire()  # 유일한 permit을 미리 점유해 세마포어를 소진시킵니다.
    monkeypatch.setattr(
        nvidia_runtime, "_REAL_MODEL_SEMAPHORE", exhausted_semaphore
    )

    with pytest.raises(RuntimeError, match="동시 호출"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="concurrency-job",
                videoPath=video_path,
                sampleFps=1,
                maxFrames=90,
            )
        )

    # 순번을 못 받았으므로 NVIDIA로의 네트워크 시도 자체가 없어야 합니다.
    assert FakeNvidiaClient.instances == []


def test_call_real_video_llm_model_releases_semaphore_after_success(
    monkeypatch, tmp_path
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    single_slot_semaphore = threading.Semaphore(1)
    monkeypatch.setattr(
        nvidia_runtime, "_REAL_MODEL_SEMAPHORE", single_slot_semaphore
    )

    video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="release-job",
            videoPath=video_path,
            sampleFps=1,
            maxFrames=90,
        )
    )

    # 호출이 끝난 뒤 permit이 반납되어 있어야, 대기 없이(timeout=0) 다시 확보할 수 있습니다.
    assert single_slot_semaphore.acquire(timeout=0) is True
    single_slot_semaphore.release()


def test_call_real_video_llm_model_releases_semaphore_even_when_call_fails(
    monkeypatch, tmp_path
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
    )
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.asset_create_status = 500
    FakeNvidiaClient.asset_create_json = {"error": "asset create failed"}
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    single_slot_semaphore = threading.Semaphore(1)
    monkeypatch.setattr(
        nvidia_runtime, "_REAL_MODEL_SEMAPHORE", single_slot_semaphore
    )

    with pytest.raises(httpx.HTTPStatusError):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="release-on-failure-job",
                videoPath=str(video_path),
            )
        )

    assert single_slot_semaphore.acquire(timeout=0) is True
    single_slot_semaphore.release()


@pytest.mark.parametrize("configured_value", ["0", "-1", "nan", "inf", "not-a-number"])
def test_resolve_nvidia_timeout_rejects_invalid_values(monkeypatch, configured_value):
    monkeypatch.setenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", configured_value)

    with pytest.raises(RuntimeError, match="must be a positive number"):
        video_llm_analysis.resolve_nvidia_timeout_seconds()


def test_call_real_video_llm_model_rejects_invalid_timeout_before_network(
    monkeypatch,
    tmp_path,
):
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS", "nan")

    with pytest.raises(RuntimeError, match="NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="invalid-timeout-job",
                videoPath=create_video_file(tmp_path),
            )
        )

    assert FakeNvidiaClient.instances == []


def test_call_real_video_llm_model_rejects_invalid_base_url_before_network(
    monkeypatch,
    tmp_path,
):
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_API_BASE_URL", "not-a-url")

    with pytest.raises(RuntimeError, match="NVIDIA_API_BASE_URL"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="invalid-base-url-job",
                videoPath=create_video_file(tmp_path),
            )
        )

    assert FakeNvidiaClient.instances == []


@pytest.mark.parametrize("duration_sec", [0.0, -5.0, float("inf"), float("nan")])
def test_call_real_video_llm_model_rejects_non_positive_or_non_finite_duration(
    monkeypatch, tmp_path, duration_sec
):
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    with pytest.raises(RuntimeError, match="durationSec"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="invalid-duration-job",
                videoPath=create_video_file(tmp_path),
                durationSec=duration_sec,
            )
        )

    assert FakeNvidiaClient.instances == []


def test_call_real_video_llm_model_rejects_duration_exceeding_configured_max(
    monkeypatch, tmp_path
):
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_MAX_DURATION_SECONDS", "3600")

    with pytest.raises(RuntimeError, match="VIDEO_LLM_MAX_DURATION_SECONDS"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="oversized-duration-job",
                videoPath=create_video_file(tmp_path),
                # 단위 착오(ms를 초로 전달)를 흉내낸 값입니다.
                durationSec=3_600_000.0,
            )
        )

    assert FakeNvidiaClient.instances == []


def test_analyze_falls_back_to_mock_when_duration_exceeds_configured_max(
    monkeypatch, tmp_path
):
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_MAX_DURATION_SECONDS", "3600")
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    client = create_client(monkeypatch, tmp_path)

    payload = analysis_payload("oversized-duration-endpoint-job")
    payload["durationSec"] = 3_600_000.0

    response = client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json=payload,
    )

    assert response.status_code == 200
    assert response.json()["model"]["generationMode"] == "FALLBACK"
    assert FakeNvidiaClient.instances == []


def test_call_real_video_llm_model_uses_natural_prompt_for_short_duration(
    monkeypatch, tmp_path
):
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
    assert "Report the real moments you actually observe" in user_text
    assert "Do not force the video into artificial sub-segments" in user_text
    # 30초 미만은 3구간 강제 분할 프롬프트를 쓰지 않습니다.
    assert "Divide the video into three temporal segments" not in user_text


def test_call_real_video_llm_model_forces_three_segments_for_long_duration(
    monkeypatch, tmp_path
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "100")

    video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="long-duration-prompt-job",
            videoPath=video_path,
            durationSec=60.0,
        )
    )

    sent_request = FakeNvidiaClient.instances[0].requests[0]
    user_text = sent_request["json"]["messages"][1]["content"][0]["text"]
    assert "The video is exactly 60.000 seconds long." in user_text
    assert "within [0, 60.000]" in user_text
    assert "must not all be 0" in user_text
    assert "Divide the video into three temporal segments" in user_text
    assert "[0, 20.000)" in user_text
    assert "[20.000, 40.000)" in user_text
    assert "[40.000, 60.000]" in user_text
    assert "include at least one observation for each segment" in user_text
    assert (
        "do not collapse all observations into a single [0, duration] range"
        in user_text
    )


@pytest.mark.parametrize("duration_sec", [0.0, 29.999])
def test_build_duration_prompt_uses_natural_prompt_below_threshold(duration_sec):
    prompt = video_llm_analysis.build_duration_prompt(duration_sec)

    assert "Divide the video into three temporal segments" not in prompt
    assert "Report the real moments you actually observe" in prompt


@pytest.mark.parametrize("duration_sec", [30.0, 45.0])
def test_build_duration_prompt_forces_segments_at_or_above_threshold(duration_sec):
    prompt = video_llm_analysis.build_duration_prompt(duration_sec)

    assert "Divide the video into three temporal segments" in prompt


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

    response = nvidia_response.normalize_video_llm_response(
        "clamp-job",
        "test-model",
        payload,
        duration_sec=4.166,
    )

    assert response["observations"]["eyeContact"][0]["startSec"] == 4.166
    assert response["observations"]["eyeContact"][0]["endSec"] == 4.166


def test_normalize_video_llm_response_clamps_negative_times_to_zero():
    item = {
        "startSec": -3.5,
        "endSec": -1.0,
        "label": "hallucinated",
        "description": "모델이 음수 시간을 반환한 경우입니다.",
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

    response = nvidia_response.normalize_video_llm_response(
        "negative-clamp-job",
        "test-model",
        payload,
        duration_sec=10.0,
    )

    assert response["observations"]["eyeContact"][0]["startSec"] == 0
    assert response["observations"]["eyeContact"][0]["endSec"] == 0


@pytest.mark.parametrize("duration_sec", [None, 10.0])
def test_clamp_observation_time_floors_negative_values_regardless_of_duration(
    duration_sec,
):
    assert (
        nvidia_response.clamp_observation_time(
            -5.0, duration_sec, "eyeContact", 0, "startSec"
        )
        == 0
    )


def test_normalize_video_llm_response_rejects_reversed_negative_times_instead_of_clamping_to_zero():
    # startSec=-1.0, endSec=-3.5는 원본 기준으로 이미 순서가 뒤집혀 있다(-3.5 < -1.0).
    # 각각 독립적으로 0으로 클램프하면 0 < 0이 되어 "정상"처럼 보이지만, 애초에
    # 유효하지 않았던 값이므로 클램프 전에 걸러져야 한다.
    item = {
        "startSec": -1.0,
        "endSec": -3.5,
        "label": "hallucinated",
        "description": "모델이 순서가 뒤집힌 음수 시간을 반환한 경우입니다.",
        "confidence": 0.7,
    }
    payload = {
        "observations": {
            "eyeContact": [item],
            "facialExpression": [],
            "gesture": [],
            "posture": [],
        },
        "globalSummary": {
            "visualDelivery": "요약",
            "mainStrength": "강점",
            "mainWeakness": "약점",
        },
    }

    with pytest.raises(ValueError, match="endSec < startSec"):
        nvidia_response.normalize_video_llm_response(
            "reversed-negative-job",
            "test-model",
            payload,
            duration_sec=10.0,
        )


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
        nvidia_response.normalize_video_llm_response(
            "no-duration-job",
            "test-model",
            payload,
        )


@pytest.mark.parametrize("field", ["startSec", "endSec", "confidence"])
@pytest.mark.parametrize("bad_value", [float("nan"), float("inf"), float("-inf")])
def test_normalize_video_llm_response_rejects_nan_and_infinite_values(field, bad_value):
    # json.loads()는 표준 JSON 사양 밖의 확장 리터럴인 NaN/Infinity를 기본적으로 허용한다.
    # 이 값들은 모든 비교 연산에서 항상 False가 되어 상/하한 클램프와 confidence 범위
    # 검사를 그대로 통과해버릴 수 있으므로, require_number()가 명시적으로 걸러내야 한다.
    item = {
        "startSec": 1.0,
        "endSec": 2.0,
        "label": "hallucinated",
        "description": "모델이 비정상적인 수치를 반환한 경우입니다.",
        "confidence": 0.7,
    }
    item[field] = bad_value

    payload = {
        "observations": {
            "eyeContact": [item],
            "facialExpression": [],
            "gesture": [],
            "posture": [],
        },
        "globalSummary": {
            "visualDelivery": "요약",
            "mainStrength": "강점",
            "mainWeakness": "약점",
        },
    }

    with pytest.raises(ValueError, match="finite number"):
        nvidia_response.normalize_video_llm_response(
            "nan-infinity-job",
            "test-model",
            payload,
            duration_sec=10.0,
        )


def test_json_loads_accepts_nan_and_infinity_literals_by_default():
    # require_number()의 방어가 왜 필요한지 보여주는 전제 조건 테스트입니다: 실제 NVIDIA
    # 응답을 파싱하는 json.loads()는 이런 값을 조용히 통과시킵니다.
    parsed = json.loads('{"a": NaN, "b": Infinity, "c": -Infinity}')

    assert math.isnan(parsed["a"])
    assert parsed["b"] == float("inf")
    assert parsed["c"] == float("-inf")


def test_call_real_video_llm_model_uses_nvcf_asset_for_large_video(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
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
    assert upload_request["headers"]["Content-Length"] == str(
        nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1
    )
    assert len(upload_request["content"]) == (
        nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1
    )
    chat_request = main_client.requests[2]
    assert chat_request["headers"]["NVCF-INPUT-ASSET-REFERENCES"] == "asset-123"
    assert (
        '<video src="data:video/mp4;asset_id,asset-123" />'
        in (chat_request["json"]["messages"][0]["content"])
    )

    cleanup_client = FakeNvidiaClient.instances[1]
    assert cleanup_client.requests == [
        {
            "method": "DELETE",
            "url": "https://api.nvcf.nvidia.com/v2/nvcf/assets/asset-123",
            "headers": {"Authorization": "Bearer nvapi-test-key"},
        }
    ]


def test_build_nvidia_video_input_from_local_file_rejects_oversized_video(
    monkeypatch, tmp_path
):
    video_path = tmp_path / "oversized.mp4"
    video_path.write_bytes(b"x" * (1024 * 1024 + 1))
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", "1")
    client = FakeNvidiaClient(timeout=120)

    with pytest.raises(ValueError, match="exceeds VIDEO_LLM_MAX_VIDEO_SIZE_MB"):
        nvidia_provider.build_nvidia_video_input_from_local_file(
            client=client,
            api_key="nvapi-test-key",
            asset_base_url="https://api.nvcf.nvidia.com/v2/nvcf",
            video_path=video_path,
            content_type="video/mp4",
            description="video-llm-analysis jobId=oversized-local-video",
        )

    assert client.requests == []


def test_iter_file_chunks_uses_bounded_chunks(tmp_path):
    video_path = tmp_path / "chunked.mp4"
    video_path.write_bytes(b"x" * (media_io.VIDEO_STREAM_CHUNK_SIZE_BYTES * 2 + 123))

    chunks = list(nvidia_provider.iter_file_chunks(video_path))

    assert [len(chunk) for chunk in chunks] == [
        media_io.VIDEO_STREAM_CHUNK_SIZE_BYTES,
        media_io.VIDEO_STREAM_CHUNK_SIZE_BYTES,
        123,
    ]


def test_upload_video_to_asset_streams_through_httpx_with_content_length(tmp_path):
    video_path = tmp_path / "httpx-stream.mp4"
    video_content = b"z" * (media_io.VIDEO_STREAM_CHUNK_SIZE_BYTES + 321)
    video_path.write_bytes(video_content)

    def handle_upload(request: httpx.Request) -> httpx.Response:
        assert request.headers["content-length"] == str(len(video_content))
        assert request.headers["content-type"] == "video/mp4"
        assert request.read() == video_content
        return httpx.Response(200)

    with httpx.Client(transport=httpx.MockTransport(handle_upload)) as client:
        nvidia_provider.upload_video_to_asset(
            client,
            "https://upload.example.test/asset",
            video_path,
            "video/mp4",
            "stream-test",
        )


def test_call_real_video_llm_model_raises_when_nvcf_asset_create_fails(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
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
        b"x" * (nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
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
        b"x" * (nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
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


def test_call_real_video_llm_model_deletes_asset_when_chat_request_fails(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "large.mp4"
    video_path.write_bytes(
        b"x" * (nvidia_provider.NVCF_INLINE_ASSET_SIZE_LIMIT_BYTES + 1)
    )
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.raised_exception = httpx.ConnectError("chat connection failed")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")

    with pytest.raises(httpx.ConnectError, match="chat connection failed"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="chat-failure-cleanup-job",
                videoPath=str(video_path),
            )
        )

    main_client = FakeNvidiaClient.instances[0]
    assert [request["method"] for request in main_client.requests] == [
        "POST",
        "PUT",
        "POST",
    ]
    cleanup_client = FakeNvidiaClient.instances[1]
    assert cleanup_client.requests == [
        {
            "method": "DELETE",
            "url": "https://api.nvcf.nvidia.com/v2/nvcf/assets/asset-123",
            "headers": {"Authorization": "Bearer nvapi-test-key"},
        }
    ]


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
    monkeypatch.setattr(nvidia_provider.time, "sleep", lambda _: None)

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
        for _ in range(nvidia_provider.NVIDIA_STATUS_POLL_MAX_ATTEMPTS)
    ]
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setattr(nvidia_provider.time, "sleep", lambda _: None)

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
        json.dumps(
            {
                "observations": {},
                "globalSummary": {
                    "visualDelivery": "요약",
                    "mainStrength": "강점",
                    "mainWeakness": "약점",
                },
            }
        ),
    )
    monkeypatch.setenv("VIDEO_LLM_ENABLED", "true")
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
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

    def __init__(self, timeout, follow_redirects=False):
        self.timeout = timeout
        self.follow_redirects = follow_redirects

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def stream(self, method, url):
        if FakeDownloadClient.raised_exception:
            raise FakeDownloadClient.raised_exception

        response = httpx.Response(
            FakeDownloadClient.response_status,
            content=FakeDownloadClient.response_content,
            headers=FakeDownloadClient.response_headers
            or {"content-type": "video/mp4"},
            request=httpx.Request(method, url),
        )
        return FakeDownloadResponseContext(response)


class FakeDownloadResponseContext:
    def __init__(self, response):
        self.response = response

    def __enter__(self):
        return self.response

    def __exit__(self, exc_type, exc_value, traceback):
        self.response.close()
        return False


def test_resolve_video_file_streams_download_and_removes_temp_file(
    monkeypatch, tmp_path
):
    FakeDownloadClient.response_status = 200
    FakeDownloadClient.response_content = b"downloaded-bytes"
    FakeDownloadClient.response_headers = {"content-type": "video/mp4"}
    FakeDownloadClient.raised_exception = None
    monkeypatch.setattr(media_io.httpx, "Client", FakeDownloadClient)

    request = VideoLlmAnalysisRequest(
        jobId="video-llm-download-1",
        videoPath=create_video_file(tmp_path),
        videoDownloadUrl="https://minio.local/uploads/video-llm-download-1/original.mp4",
    )

    with media_io.resolve_video_file(request) as (video_path, content_type):
        assert video_path.read_bytes() == b"downloaded-bytes"
        assert video_path != Path(request.videoPath)
        assert video_path.exists()

    assert content_type == "video/mp4"
    assert video_path.exists() is False


def test_resolve_video_file_falls_back_to_local_path_when_download_fails(
    monkeypatch, tmp_path
):
    FakeDownloadClient.raised_exception = httpx.ConnectError("connection failed")
    monkeypatch.setattr(media_io.httpx, "Client", FakeDownloadClient)

    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-download-2",
        videoPath=video_path,
        videoDownloadUrl="https://minio.local/uploads/video-llm-download-2/original.mp4",
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(video_path)
        assert resolved_path.read_bytes() == b"fake mp4 bytes"

    assert content_type == "video/mp4"


@pytest.mark.parametrize(
    "bad_url",
    [
        "file:///etc/passwd",
        "ftp://example.com/video.mp4",
        "not-a-url",
        "",
        "//example.com/video.mp4",
    ],
)
def test_validate_video_download_url_rejects_non_http_urls(bad_url):
    with pytest.raises(ValueError, match="absolute http"):
        media_io.validate_video_download_url(bad_url)


def test_validate_video_download_url_accepts_http_and_https():
    media_io.validate_video_download_url("https://minio.local/uploads/x/original.mp4")
    media_io.validate_video_download_url("http://minio.local/uploads/x/original.mp4")


def test_validate_video_download_url_rejects_host_outside_allowlist(monkeypatch):
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    with pytest.raises(ValueError, match="not in VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS"):
        media_io.validate_video_download_url("https://attacker.example.com/steal.mp4")


def test_validate_video_download_url_rejects_internal_metadata_endpoint(monkeypatch):
    # 클라우드 메타데이터 엔드포인트(169.254.169.254)로 향하는 SSRF 시도를 막는다.
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    with pytest.raises(ValueError, match="not in VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS"):
        media_io.validate_video_download_url("http://169.254.169.254/latest/meta-data/")


def test_resolve_video_file_falls_back_to_local_path_for_disallowed_host(
    monkeypatch, tmp_path
):
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_DOWNLOAD_HOSTS", "minio.local:443")
    request_attempted = {"called": False}

    class TrackingFakeDownloadClient(FakeDownloadClient):
        def stream(self, method, url):
            request_attempted["called"] = True
            return super().stream(method, url)

    monkeypatch.setattr(media_io.httpx, "Client", TrackingFakeDownloadClient)

    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-ssrf-allowlist-job",
        videoPath=video_path,
        videoDownloadUrl="https://attacker.example.com/steal.mp4",
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(video_path)

    assert request_attempted["called"] is False


def test_resolve_video_file_falls_back_to_local_path_on_redirect_response(
    monkeypatch, tmp_path
):
    FakeDownloadClient.response_status = 302
    FakeDownloadClient.response_content = b""
    FakeDownloadClient.response_headers = {
        "location": "https://attacker.example.com/steal.mp4"
    }
    FakeDownloadClient.raised_exception = None
    monkeypatch.setattr(media_io.httpx, "Client", FakeDownloadClient)

    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-redirect-job",
        videoPath=video_path,
        videoDownloadUrl="https://minio.local/uploads/video-llm-redirect-job/original.mp4",
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(video_path)
        assert resolved_path.read_bytes() == b"fake mp4 bytes"


def test_resolve_video_file_falls_back_to_local_path_without_attempting_request_for_bad_url(
    monkeypatch, tmp_path
):
    request_attempted = {"called": False}

    class TrackingFakeDownloadClient(FakeDownloadClient):
        def stream(self, method, url):
            request_attempted["called"] = True
            return super().stream(method, url)

    monkeypatch.setattr(media_io.httpx, "Client", TrackingFakeDownloadClient)

    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-ssrf-job",
        videoPath=video_path,
        # 스킴이 http(s)가 아닌 값입니다 - httpx가 이 스킴을 어떻게 다루든, 애초에
        # 요청을 시도조차 하지 않아야 합니다.
        videoDownloadUrl="file:///etc/passwd",
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(video_path)

    assert request_attempted["called"] is False


def test_validate_local_video_path_accepts_path_inside_allowed_base_dir(
    monkeypatch, tmp_path
):
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR", str(tmp_path))
    inside_path = tmp_path / "uploads" / "job-1" / "original.mp4"
    inside_path.parent.mkdir(parents=True)
    inside_path.write_bytes(b"x")

    # 예외를 던지지 않으면 통과입니다.
    media_io.validate_local_video_path(inside_path)


def test_validate_local_video_path_rejects_path_outside_allowed_base_dir(
    monkeypatch, tmp_path
):
    allowed_dir = tmp_path / "storage"
    allowed_dir.mkdir()
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR", str(allowed_dir))

    outside_path = tmp_path / "etc" / "passwd"
    outside_path.parent.mkdir(parents=True)
    outside_path.write_bytes(b"secret")

    with pytest.raises(ValueError, match="must be inside"):
        media_io.validate_local_video_path(outside_path)


def test_call_real_video_llm_model_rejects_local_path_outside_allowed_base_dir(
    monkeypatch, tmp_path
):
    allowed_dir = tmp_path / "storage"
    allowed_dir.mkdir()
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR", str(allowed_dir))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))

    outside_path = tmp_path / "etc" / "passwd"
    outside_path.parent.mkdir(parents=True)
    outside_path.write_bytes(b"secret")

    with pytest.raises(ValueError, match="must be inside"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="path-traversal-job",
                videoPath=str(outside_path),
            )
        )

    # 경로가 거부됐으므로 NVIDIA에는 아무것도 전송되지 않아야 합니다.
    assert FakeNvidiaClient.instances == []


def test_resolve_video_file_uses_local_path_when_no_download_url(tmp_path):
    video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-no-url",
        videoPath=video_path,
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(video_path)
        assert resolved_path.read_bytes() == b"fake mp4 bytes"

    assert content_type == "video/mp4"


def test_resolve_video_file_rejects_oversized_download_and_removes_partial_file(
    monkeypatch,
    tmp_path,
):
    oversized_content = b"x" * (1024 * 1024 + 1)
    FakeDownloadClient.response_status = 200
    FakeDownloadClient.response_content = oversized_content
    FakeDownloadClient.response_headers = {
        "content-type": "video/mp4",
        "content-length": str(len(oversized_content)),
    }
    FakeDownloadClient.raised_exception = None
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", "1")
    monkeypatch.setattr(media_io.httpx, "Client", FakeDownloadClient)

    local_video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-oversized-download",
        videoPath=local_video_path,
        videoDownloadUrl="https://minio.local/uploads/oversized/original.mp4",
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(local_video_path)
        assert resolved_path.read_bytes() == b"fake mp4 bytes"

    assert content_type == "video/mp4"


def test_resolve_video_file_removes_partial_file_when_stream_exceeds_limit_without_length(
    monkeypatch,
    tmp_path,
):
    oversized_content = b"x" * (1024 * 1024 + 1)
    FakeDownloadClient.response_status = 200
    FakeDownloadClient.response_content = oversized_content
    FakeDownloadClient.response_headers = {"content-type": "video/mp4"}
    FakeDownloadClient.raised_exception = None
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", "1")
    monkeypatch.setattr(media_io.httpx, "Client", FakeDownloadClient)

    real_named_temporary_file = tempfile.NamedTemporaryFile

    def create_tracked_temp_file(*args, **kwargs):
        return real_named_temporary_file(*args, dir=tmp_path, **kwargs)

    monkeypatch.setattr(
        media_io.tempfile,
        "NamedTemporaryFile",
        create_tracked_temp_file,
    )

    local_video_path = create_video_file(tmp_path)
    request = VideoLlmAnalysisRequest(
        jobId="video-llm-stream-limit",
        videoPath=local_video_path,
        videoDownloadUrl="https://minio.local/uploads/oversized/original.mp4",
    )

    with media_io.resolve_video_file(request) as (resolved_path, content_type):
        assert resolved_path == Path(local_video_path)

    assert content_type == "video/mp4"
    assert list(tmp_path.glob("video-llm-video-llm-stream-limit-*")) == []


@pytest.mark.parametrize("configured_value", ["0", "-1", "not-a-number"])
def test_resolve_video_max_size_rejects_invalid_values(monkeypatch, configured_value):
    monkeypatch.setenv("VIDEO_LLM_MAX_VIDEO_SIZE_MB", configured_value)

    with pytest.raises(RuntimeError, match="must be a positive integer"):
        media_io.resolve_video_max_size_bytes()


def test_split_video_into_segments_creates_expected_segment_files(tmp_path):
    video_path = Path(create_real_video_file(tmp_path, duration_seconds=5))

    with video_llm_analysis.split_video_into_segments(
        video_path, chunk_duration_seconds=2, total_duration_sec=5
    ) as segments:
        assert len(segments) == 3
        # segments는 (원래 청크 인덱스, 파일 경로) 튜플 목록입니다.
        assert [index for index, _ in segments] == [0, 1, 2]
        for _, segment_path in segments:
            assert segment_path.exists()
            assert segment_path.stat().st_size > 0
        segment_dir = segments[0][1].parent

    # 컨텍스트를 벗어나면 임시 디렉터리가 정리되어야 합니다.
    assert not segment_dir.exists()


def test_split_video_into_segments_raises_when_input_is_not_a_valid_video(tmp_path):
    invalid_path = tmp_path / "not-a-video.mp4"
    invalid_path.write_bytes(b"this is not a real video file")

    with pytest.raises(subprocess.CalledProcessError):
        with video_llm_analysis.split_video_into_segments(
            invalid_path, chunk_duration_seconds=2, total_duration_sec=5
        ):
            pass


def test_call_real_video_llm_model_uses_single_call_for_short_video(
    monkeypatch, tmp_path
):
    video_path = create_video_file(tmp_path)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "100")

    video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="short-video-job",
            videoPath=video_path,
            durationSec=10.0,
            sampleFps=1,
            maxFrames=90,
        )
    )

    assert len(FakeNvidiaClient.instances) == 1


def test_call_real_video_llm_model_splits_long_video_and_merges_segment_results(
    monkeypatch, tmp_path
):
    video_path = create_real_video_file(tmp_path, duration_seconds=5)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    # 5초 영상을 2.5초 단위로 나누면 세그먼트 2개(각 2.5초)로 정확히 떨어집니다.
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "2.5")

    response = video_llm_analysis.call_real_video_llm_model(
        VideoLlmAnalysisRequest(
            jobId="chunked-job",
            videoPath=video_path,
            durationSec=5.0,
            sampleFps=1,
            maxFrames=90,
        )
    )

    # 세그먼트 2개 = NVIDIA 호출 2번.
    assert len(FakeNvidiaClient.instances) == 2
    assert response["status"] == "success"
    assert response["model"]["generationMode"] == "REAL"

    # nvidia_model_payload()의 각 세그먼트 응답은 카테고리당 관찰 1개(로컬 startSec=1,
    # endSec=3, 세그먼트 길이 2.5초 안에 들어옴)라, 병합 후에는 세그먼트 수만큼 있어야 합니다.
    eye_contact = response["observations"]["eyeContact"]
    assert len(eye_contact) == 2

    # 세그먼트별 관찰(로컬 startSec=1, endSec=3)은 먼저 세그먼트 길이(2.5초)로 클램프되어
    # endSec=2.5가 된 뒤, 세그먼트 시작 offset만큼 더해집니다: 세그먼트0은 [1, 2.5],
    # 세그먼트1(offset=2.5)은 [3.5, 5.0].
    offsets = sorted(item["startSec"] for item in eye_contact)
    assert offsets[0] == pytest.approx(1.0)
    assert offsets[1] == pytest.approx(3.5)
    assert all(item["endSec"] <= 5.0 for item in eye_contact)

    # 두 세그먼트의 요약이 모두 최종 globalSummary에 반영되어야 합니다(단순 이어붙이기).
    assert (
        response["globalSummary"]["visualDelivery"].count(
            "전반적으로 안정적인 발표 태도입니다."
        )
        == 2
    )


def test_call_real_video_llm_model_stops_chunk_processing_at_total_deadline(
    monkeypatch,
    tmp_path,
):
    video_path = create_real_video_file(tmp_path, duration_seconds=5)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "2.5")
    monkeypatch.setenv("VIDEO_LLM_TOTAL_TIMEOUT_SECONDS", "1")

    monotonic_clock = {"seconds": 0.0}
    monkeypatch.setattr(
        video_llm_analysis.time,
        "monotonic",
        lambda: monotonic_clock["seconds"],
    )

    original_post = FakeNvidiaClient.post
    provider_call_count = {"value": 0}

    def post_that_exhausts_deadline(self, url, headers, json, timeout=None):
        response = original_post(self, url, headers, json, timeout=timeout)
        if url.endswith("/chat/completions"):
            provider_call_count["value"] += 1
            monotonic_clock["seconds"] = 2.0
        return response

    monkeypatch.setattr(FakeNvidiaClient, "post", post_that_exhausts_deadline)

    with pytest.raises(
        TimeoutError,
        match="operation=nvidia_response",
    ):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="total-deadline-job",
                videoPath=video_path,
                durationSec=5.0,
                sampleFps=1,
                maxFrames=90,
            )
        )

    # 첫 세그먼트 응답이 전체 deadline을 넘긴 즉시 중단되어, 두 번째 공급자 호출과
    # 추가 비용이 발생하지 않아야 합니다.
    assert provider_call_count["value"] == 1


def test_call_real_video_llm_model_in_chunks_rejects_partial_real_result_when_one_segment_fails(
    monkeypatch, tmp_path
):
    video_path = create_real_video_file(tmp_path, duration_seconds=5)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "2.5")

    call_count = {"n": 0}
    original_post = FakeNvidiaClient.post

    def flaky_post(self, url, headers, json, timeout=None):
        if url.endswith("/chat/completions"):
            call_count["n"] += 1
            if call_count["n"] == 1:
                raise httpx.ConnectError(
                    "simulated segment network failure",
                    request=httpx.Request("POST", url),
                )
        return original_post(self, url, headers, json, timeout=timeout)

    monkeypatch.setattr(FakeNvidiaClient, "post", flaky_post)

    with pytest.raises(httpx.ConnectError, match="simulated segment network failure"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="partial-failure-job",
                videoPath=video_path,
                durationSec=5.0,
                sampleFps=1,
                maxFrames=90,
            )
        )

    # 첫 세그먼트 실패 즉시 전체 REAL 호출이 실패하므로 누락 구간을 숨긴 부분 결과가 없다.
    assert call_count["n"] == 1


def test_call_real_video_llm_model_in_chunks_rejects_missing_middle_segment(
    monkeypatch, tmp_path
):
    video_path = create_real_video_file(tmp_path, duration_seconds=7.5)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    # 7.5초 영상을 2.5초 단위로 나누면 세그먼트 3개(인덱스 0, 1, 2)가 나옵니다.
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "2.5")

    original_run = subprocess.run

    def flaky_split_run(args, **kwargs):
        output_path = Path(args[-1])
        if output_path.name.startswith("segment-1."):
            # ffmpeg 호출 자체는 "성공"했지만 빈 파일을 만든 상황(split_video_into_segments가
            # 이미 방어하는 케이스)을 재현합니다 - 세그먼트 1(원래 offset=2.5)이 통째로
            # 목록에서 빠집니다.
            output_path.write_bytes(b"")
            return subprocess.CompletedProcess(args, returncode=0)
        return original_run(args, **kwargs)

    monkeypatch.setattr(video_pipeline.subprocess, "run", flaky_split_run)

    with pytest.raises(RuntimeError, match="빈 출력"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="middle-segment-empty-job",
                videoPath=video_path,
                durationSec=7.5,
                sampleFps=1,
                maxFrames=90,
            )
        )

    # 분할이 완전하지 않으면 NVIDIA 호출 전에 실패해 비용과 부분 REAL 결과 생성을 막는다.
    assert FakeNvidiaClient.instances == []


def test_call_real_video_llm_model_in_chunks_raises_when_all_segments_fail(
    monkeypatch, tmp_path
):
    video_path = create_real_video_file(tmp_path, duration_seconds=5)
    install_fake_nvidia_client(monkeypatch, json.dumps(nvidia_model_payload()))
    FakeNvidiaClient.raised_exception = httpx.ConnectError(
        "simulated total failure", request=httpx.Request("POST", "https://example.com")
    )
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("VIDEO_LLM_CHUNK_DURATION_SECONDS", "2.5")

    with pytest.raises(httpx.ConnectError, match="simulated total failure"):
        video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="total-failure-job",
                videoPath=video_path,
                durationSec=5.0,
                sampleFps=1,
                maxFrames=90,
            )
        )
