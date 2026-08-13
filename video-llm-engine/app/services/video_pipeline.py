from contextlib import contextmanager
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any, Callable, Dict, Iterator, Protocol

import imageio_ffmpeg

from app.core.settings import get_settings
from app.services import deadline, media_io, nvidia_response


class ChunkedVideoRequest(media_io.VideoFileRequest, Protocol):
    sampleFps: int
    maxFrames: int
    durationSec: float


ChatCompletion = Callable[..., Dict[str, Any]]


def analyze_video_in_chunks(
    request: ChunkedVideoRequest,
    api_key: str,
    model: str,
    base_url: str,
    asset_base_url: str,
    timeout_seconds: float,
    chunk_duration_seconds: float,
    deadline_monotonic: float | None,
    *,
    call_chat_completion: ChatCompletion,
    split_timeout_seconds: float | None = None,
) -> Dict[str, Any]:
    """긴 영상을 실제 구간으로 나눠 분석하고 전체 영상 타임라인으로 병합합니다.

    세그먼트가 하나라도 누락되거나 호출에 실패하면 부분 결과를 반환하지 않고 예외를
    그대로 전달합니다. 상위 API가 STRICT/DEGRADED/requireReal 정책을 결정합니다.
    """
    total_segment_count = math.ceil(request.durationSec / chunk_duration_seconds)

    with media_io.resolve_video_file(request, deadline_monotonic) as (
        video_path,
        content_type,
    ):
        with split_video_into_segments(
            video_path,
            chunk_duration_seconds,
            request.durationSec,
            deadline_monotonic,
            split_timeout_seconds=split_timeout_seconds,
        ) as segment_paths:
            if len(segment_paths) != total_segment_count:
                raise RuntimeError(
                    "Video LLM 세그먼트 생성 결과가 예상 개수와 다릅니다. "
                    f"expected={total_segment_count}, actual={len(segment_paths)}"
                )

            merged_observations: Dict[str, list] = {
                category: [] for category in nvidia_response.OBSERVATION_CATEGORIES
            }
            summary_parts: Dict[str, list] = {
                field: [] for field in nvidia_response.SUMMARY_FIELDS
            }

            for original_index, segment_path in segment_paths:
                segment_start_offset = original_index * chunk_duration_seconds
                segment_local_duration = min(
                    chunk_duration_seconds,
                    request.durationSec - segment_start_offset,
                )
                segment_model_json = call_chat_completion(
                    api_key=api_key,
                    model=model,
                    base_url=base_url,
                    asset_base_url=asset_base_url,
                    timeout_seconds=timeout_seconds,
                    job_id=f"{request.jobId}-segment-{original_index}",
                    video_path=segment_path,
                    content_type=content_type,
                    duration_hint_sec=segment_local_duration,
                    sample_fps=request.sampleFps,
                    max_frames=request.maxFrames,
                    deadline_monotonic=deadline_monotonic,
                )
                segment_normalized = nvidia_response.normalize_video_llm_response(
                    request.jobId,
                    model,
                    segment_model_json,
                    segment_local_duration,
                )
                _merge_segment_response(
                    merged_observations,
                    summary_parts,
                    segment_normalized,
                    segment_start_offset,
                    segment_local_duration,
                )

    merged_model_json = {
        "observations": merged_observations,
        "globalSummary": {
            field: " ".join(parts) for field, parts in summary_parts.items()
        },
    }
    return nvidia_response.normalize_video_llm_response(
        request.jobId,
        model,
        merged_model_json,
        request.durationSec,
    )


def _merge_segment_response(
    merged_observations: Dict[str, list],
    summary_parts: Dict[str, list],
    segment_normalized: Dict[str, Any],
    segment_start_offset: float,
    segment_local_duration: float,
) -> None:
    for category in nvidia_response.OBSERVATION_CATEGORIES:
        for item in segment_normalized["observations"][category]:
            offset_item = dict(item)
            offset_item["startSec"] = round(
                item["startSec"] + segment_start_offset,
                3,
            )
            offset_item["endSec"] = round(
                item["endSec"] + segment_start_offset,
                3,
            )
            merged_observations[category].append(offset_item)

    segment_end_offset = segment_start_offset + segment_local_duration
    for field in nvidia_response.SUMMARY_FIELDS:
        summary_parts[field].append(
            f"[{segment_start_offset:.0f}-{segment_end_offset:.0f}s] "
            f"{segment_normalized['globalSummary'][field]}"
        )


@contextmanager
def split_video_into_segments(
    video_path: Path,
    chunk_duration_seconds: float,
    total_duration_sec: float,
    deadline_monotonic: float | None = None,
    *,
    split_timeout_seconds: float | None = None,
) -> Iterator[list[tuple[int, Path]]]:
    """ffmpeg로 영상을 나누고 원래 청크 인덱스와 임시 경로를 반환합니다."""
    segment_count = math.ceil(total_duration_sec / chunk_duration_seconds)
    output_dir = Path(tempfile.mkdtemp(prefix="video-llm-segments-"))
    ffmpeg_executable = imageio_ffmpeg.get_ffmpeg_exe()
    effective_configured_timeout = (
        split_timeout_seconds
        if split_timeout_seconds is not None
        else get_settings().segment_split_timeout_seconds
    )
    suffix = video_path.suffix or ".mp4"

    try:
        segment_paths: list[tuple[int, Path]] = []
        for index in range(segment_count):
            start_sec = index * chunk_duration_seconds
            output_path = output_dir / f"segment-{index}{suffix}"
            effective_split_timeout_seconds = deadline.remaining_timeout_seconds(
                deadline_monotonic,
                effective_configured_timeout,
                f"segment_split_{index}",
            )
            subprocess.run(
                [
                    ffmpeg_executable,
                    "-y",
                    "-ss",
                    str(start_sec),
                    "-t",
                    str(chunk_duration_seconds),
                    "-i",
                    str(video_path),
                    "-c",
                    "copy",
                    "-avoid_negative_ts",
                    "make_zero",
                    str(output_path),
                ],
                check=True,
                capture_output=True,
                timeout=effective_split_timeout_seconds,
            )
            deadline.ensure_within(deadline_monotonic, f"segment_split_{index}")

            if not output_path.exists() or output_path.stat().st_size == 0:
                raise RuntimeError(
                    "ffmpeg가 세그먼트를 만들지 못했습니다(빈 출력). "
                    f"segment={index + 1}/{segment_count}"
                )
            segment_paths.append((index, output_path))

        if not segment_paths:
            raise RuntimeError(
                "ffmpeg가 영상을 세그먼트로 나누지 못했습니다(생성된 세그먼트 없음)."
            )
        yield segment_paths
    finally:
        shutil.rmtree(output_dir, ignore_errors=True)
