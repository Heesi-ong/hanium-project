from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
import subprocess

import pytest

from app.services import media_io, nvidia_response, video_pipeline


@dataclass
class FakeChunkedRequest:
    jobId: str = "pipeline-job"
    videoPath: str = "/storage/uploads/pipeline-job/video.mp4"
    videoDownloadUrl: str | None = None
    sampleFps: int = 1
    maxFrames: int = 90
    durationSec: float = 5.0


def _model_payload() -> dict:
    observation = {
        "startSec": 1.0,
        "endSec": 3.0,
        "label": "stable",
        "description": "안정적으로 관찰됩니다.",
        "confidence": 0.8,
    }
    return {
        "observations": {
            category: [dict(observation)]
            for category in nvidia_response.OBSERVATION_CATEGORIES
        },
        "globalSummary": {
            "visualDelivery": "전달 요약",
            "mainStrength": "강점 요약",
            "mainWeakness": "개선 요약",
        },
    }


def _install_resolved_video(monkeypatch, tmp_path):
    video_path = tmp_path / "source.mp4"
    video_path.write_bytes(b"source")

    @contextmanager
    def resolved_video(_request, _deadline_monotonic=None):
        yield video_path, "video/mp4"

    monkeypatch.setattr(media_io, "resolve_video_file", resolved_video)
    return video_path


def test_analyze_video_in_chunks_offsets_all_segments_and_merges_summaries(
    monkeypatch,
    tmp_path,
):
    _install_resolved_video(monkeypatch, tmp_path)
    segment_paths = [tmp_path / "segment-0.mp4", tmp_path / "segment-1.mp4"]
    for segment_path in segment_paths:
        segment_path.write_bytes(b"segment")

    @contextmanager
    def split_segments(*_args, **_kwargs):
        yield list(enumerate(segment_paths))

    monkeypatch.setattr(video_pipeline, "split_video_into_segments", split_segments)
    called_job_ids = []

    def call_chat_completion(**kwargs):
        called_job_ids.append(kwargs["job_id"])
        return _model_payload()

    response = video_pipeline.analyze_video_in_chunks(
        request=FakeChunkedRequest(),
        api_key="test-key",
        model="test-model",
        base_url="https://example.test/v1",
        asset_base_url="https://example.test/v2",
        timeout_seconds=30,
        chunk_duration_seconds=2.5,
        deadline_monotonic=None,
        call_chat_completion=call_chat_completion,
        split_timeout_seconds=10,
    )

    assert called_job_ids == ["pipeline-job-segment-0", "pipeline-job-segment-1"]
    eye_contact = response["observations"]["eyeContact"]
    assert [item["startSec"] for item in eye_contact] == [1.0, 3.5]
    assert [item["endSec"] for item in eye_contact] == [2.5, 5.0]
    assert response["globalSummary"]["visualDelivery"] == (
        "[0-2s] 전달 요약 [2-5s] 전달 요약"
    )


def test_analyze_video_in_chunks_rejects_missing_segment_before_provider_call(
    monkeypatch,
    tmp_path,
):
    _install_resolved_video(monkeypatch, tmp_path)
    only_segment = tmp_path / "segment-0.mp4"
    only_segment.write_bytes(b"segment")

    @contextmanager
    def incomplete_segments(*_args, **_kwargs):
        yield [(0, only_segment)]

    monkeypatch.setattr(
        video_pipeline,
        "split_video_into_segments",
        incomplete_segments,
    )
    provider_called = False

    def call_chat_completion(**_kwargs):
        nonlocal provider_called
        provider_called = True
        return _model_payload()

    with pytest.raises(RuntimeError, match="expected=2, actual=1"):
        video_pipeline.analyze_video_in_chunks(
            request=FakeChunkedRequest(),
            api_key="test-key",
            model="test-model",
            base_url="https://example.test/v1",
            asset_base_url="https://example.test/v2",
            timeout_seconds=30,
            chunk_duration_seconds=2.5,
            deadline_monotonic=None,
            call_chat_completion=call_chat_completion,
            split_timeout_seconds=10,
        )

    assert provider_called is False


def test_split_video_into_segments_preserves_indices_and_cleans_temp_directory(
    monkeypatch,
    tmp_path,
):
    video_path = tmp_path / "source.mp4"
    video_path.write_bytes(b"source")

    def fake_run(args, **kwargs):
        output_path = Path(args[-1])
        output_path.write_bytes(b"segment")
        return subprocess.CompletedProcess(args, returncode=0)

    monkeypatch.setattr(video_pipeline.subprocess, "run", fake_run)
    monkeypatch.setattr(
        video_pipeline.imageio_ffmpeg,
        "get_ffmpeg_exe",
        lambda: "/test/ffmpeg",
    )

    with video_pipeline.split_video_into_segments(
        video_path,
        chunk_duration_seconds=2,
        total_duration_sec=5,
        split_timeout_seconds=10,
    ) as segments:
        assert [index for index, _ in segments] == [0, 1, 2]
        segment_dir = segments[0][1].parent
        assert all(path.read_bytes() == b"segment" for _, path in segments)

    assert not segment_dir.exists()
