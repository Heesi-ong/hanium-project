import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time

import pytest
from fastapi.testclient import TestClient

from app.main import app


def _provider_model_payload() -> dict:
    observation = {
        "startSec": 0,
        "endSec": 1,
        "label": "stable",
        "description": "발표자가 안정적으로 보입니다.",
        "confidence": 0.9,
    }
    return {
        "observations": {
            "eyeContact": [observation],
            "facialExpression": [observation],
            "gesture": [observation],
            "posture": [observation],
        },
        "globalSummary": {
            "visualDelivery": "전반적으로 안정적입니다.",
            "mainStrength": "자세가 안정적입니다.",
            "mainWeakness": "제스처 다양성을 보완할 수 있습니다.",
        },
    }


class _FaultInjectingProviderHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(content_length)
        self.server.request_count += 1

        if self.server.mode == "timeout":
            time.sleep(0.2)

        if self.server.mode in {"5xx", "timeout"}:
            self._send_json(503, {"error": "simulated provider outage"})
            return

        self._send_json(
            200,
            {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                _provider_model_payload(),
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        )

    def _send_json(self, status_code: int, payload: dict) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status_code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            # timeout 주입에서는 엔진이 먼저 연결을 닫는 것이 기대 동작입니다.
            pass

    def log_message(self, format, *args):
        return


@pytest.fixture
def fault_injecting_provider():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _FaultInjectingProviderHandler)
    server.mode = "success"
    server.request_count = 0
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def _configure_real_provider(
    monkeypatch,
    tmp_path,
    provider,
    policy: str,
    provider_timeout_seconds: float,
) -> str:
    video_path = tmp_path / "inline-video.mp4"
    video_path.write_bytes(b"small inline test video")

    provider_base_url = f"http://127.0.0.1:{provider.server_port}/v1"
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", "shared-secret")
    monkeypatch.setenv("LOG_DIR", str(tmp_path / "logs"))
    monkeypatch.setenv("VIDEO_LLM_POLICY", policy)
    monkeypatch.setenv("VIDEO_LLM_BACKEND", "external-api")
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-test-key")
    monkeypatch.setenv("NVIDIA_API_BASE_URL", provider_base_url)
    monkeypatch.setenv("NVIDIA_ASSET_API_BASE_URL", provider_base_url)
    monkeypatch.setenv(
        "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
        str(provider_timeout_seconds),
    )
    monkeypatch.setenv("VIDEO_LLM_TOTAL_TIMEOUT_SECONDS", "2")
    monkeypatch.setenv("VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR", str(tmp_path))
    return str(video_path)


def _analyze(client: TestClient, video_path: str, job_id: str):
    return client.post(
        "/api/video-llm/analyze",
        headers={"X-Internal-Api-Key": "shared-secret"},
        json={
            "jobId": job_id,
            "videoPath": video_path,
            "sampleFps": 1,
            "maxFrames": 90,
            "durationSec": 10,
        },
    )


@pytest.mark.parametrize(
    "policy,provider_mode,provider_timeout_seconds,expected_status,expected_mode",
    [
        ("STRICT", "5xx", 1, 502, None),
        ("DEGRADED", "5xx", 1, 200, "FALLBACK"),
        ("STRICT", "timeout", 0.05, 502, None),
        ("DEGRADED", "timeout", 0.05, 200, "FALLBACK"),
    ],
)
def test_real_http_provider_failures_follow_runtime_policy(
    monkeypatch,
    tmp_path,
    fault_injecting_provider,
    policy,
    provider_mode,
    provider_timeout_seconds,
    expected_status,
    expected_mode,
):
    fault_injecting_provider.mode = provider_mode
    video_path = _configure_real_provider(
        monkeypatch,
        tmp_path,
        fault_injecting_provider,
        policy,
        provider_timeout_seconds,
    )

    with TestClient(app) as client:
        response = _analyze(
            client,
            video_path,
            f"{policy.lower()}-{provider_mode}-job",
        )

    assert response.status_code == expected_status
    if expected_mode is None:
        assert response.json()["detail"]["code"] == "VIDEO_LLM_REAL_MODEL_FAILED"
    else:
        assert response.json()["model"]["generationMode"] == expected_mode
    assert fault_injecting_provider.request_count == 1


def test_strict_policy_recovers_to_real_after_provider_5xx_clears(
    monkeypatch,
    tmp_path,
    fault_injecting_provider,
):
    video_path = _configure_real_provider(
        monkeypatch,
        tmp_path,
        fault_injecting_provider,
        "STRICT",
        1,
    )

    with TestClient(app) as client:
        fault_injecting_provider.mode = "5xx"
        failed_response = _analyze(client, video_path, "strict-recovery-failed")

        fault_injecting_provider.mode = "success"
        recovered_response = _analyze(client, video_path, "strict-recovery-real")

    assert failed_response.status_code == 502
    assert recovered_response.status_code == 200
    assert recovered_response.json()["model"]["generationMode"] == "REAL"
    assert fault_injecting_provider.request_count == 2
