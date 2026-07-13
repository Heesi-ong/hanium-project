import json
import os
import shutil
import subprocess
import time
from pathlib import Path

import httpx
import pytest

from app.api import video_llm_analysis
from app.api.video_llm_analysis import VideoLlmAnalysisRequest


ENV_KEYS = {
    "NVIDIA_API_KEY",
    "NVIDIA_VIDEO_LLM_MODEL",
    "NVIDIA_API_BASE_URL",
    "NVIDIA_ASSET_API_BASE_URL",
    "NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS",
}


def load_local_env_file() -> None:
    env_path = Path(__file__).resolve().parents[1] / ".env"
    if not env_path.exists():
        return

    for line in env_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue

        key, value = stripped.split("=", 1)
        key = key.strip()
        if key in ENV_KEYS and not os.getenv(key):
            os.environ[key] = value.strip().strip('"').strip("'")


def create_sample_mp4(tmp_path: Path) -> Path:
    ffmpeg_path = shutil.which("ffmpeg")
    if not ffmpeg_path:
        pytest.skip("ffmpeg is required to generate the live NVIDIA sample mp4.")

    output_path = tmp_path / "nvidia-live-sample.mp4"
    subprocess.run(
        [
            ffmpeg_path,
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=320x240:rate=10",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=16000",
            "-t",
            "4",
            "-pix_fmt",
            "yuv420p",
            "-c:v",
            "libx264",
            "-c:a",
            "aac",
            "-movflags",
            "+faststart",
            str(output_path),
        ],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return output_path


def print_live_result(label: str, payload) -> None:
    print(f"NVIDIA_LIVE_{label}=" + json.dumps(payload, ensure_ascii=False, indent=2)[:6000])


@pytest.mark.live
def test_nvidia_nemotron_omni_live_payload_and_response(monkeypatch, tmp_path):
    load_local_env_file()
    if not os.getenv("NVIDIA_API_KEY"):
        pytest.skip("NVIDIA_API_KEY is not configured.")

    sample_video = create_sample_mp4(tmp_path)
    captured = {}
    original_extract = video_llm_analysis.extract_chat_completion_content

    def capture_raw_response(response_json):
        captured["response_json"] = response_json
        print_live_result("RAW_RESPONSE", response_json)
        return original_extract(response_json)

    monkeypatch.setattr(
        video_llm_analysis,
        "extract_chat_completion_content",
        capture_raw_response,
    )

    started_at = time.monotonic()
    try:
        normalized = video_llm_analysis.call_real_video_llm_model(
            VideoLlmAnalysisRequest(
                jobId="nvidia-live-smoke",
                videoPath=str(sample_video),
                sampleFps=1,
                maxFrames=20,
            )
        )
    except httpx.HTTPStatusError as exc:
        response = exc.response
        rate_limit_headers = {
            key: value
            for key, value in response.headers.items()
            if key.lower() in {
                "retry-after",
                "x-ratelimit-limit",
                "x-ratelimit-remaining",
                "x-ratelimit-reset",
            }
        }
        print_live_result(
            "HTTP_ERROR",
            {
                "status_code": response.status_code,
                "headers": rate_limit_headers,
                "body": response.text[:4000],
            },
        )
        raise
    finally:
        elapsed_ms = int((time.monotonic() - started_at) * 1000)
        print_live_result(
            "METADATA",
            {
                "elapsedMs": elapsed_ms,
                "sampleVideoBytes": sample_video.stat().st_size,
                "model": os.getenv(
                    "NVIDIA_VIDEO_LLM_MODEL",
                    video_llm_analysis.NVIDIA_DEFAULT_MODEL,
                ),
                "baseUrl": os.getenv(
                    "NVIDIA_API_BASE_URL",
                    video_llm_analysis.NVIDIA_DEFAULT_API_BASE_URL,
                ),
            },
        )

    print_live_result("NORMALIZED", normalized)

    assert captured["response_json"]["choices"][0]["message"]["content"]
    assert normalized["jobId"] == "nvidia-live-smoke"
    assert normalized["status"] == "success"
    assert normalized["model"]["generationMode"] == "REAL"
    assert set(normalized["observations"].keys()) == {
        "eyeContact",
        "facialExpression",
        "gesture",
        "posture",
    }
    assert normalized["globalSummary"]["visualDelivery"]
