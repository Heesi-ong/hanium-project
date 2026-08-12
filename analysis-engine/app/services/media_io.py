import logging
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, List

import cv2
import imageio_ffmpeg
import mediapipe as mp
import requests

from app.core.network_security import validate_local_video_path, validate_video_download_url
from app.core.paths import resolve_project_root
from app.core.settings import get_settings

logger = logging.getLogger("analysis-engine")

MAX_EXTRACTED_FRAMES = 20
FRAME_EXTRACT_INTERVAL_SEC = 1
VIDEO_DOWNLOAD_TIMEOUT_SECONDS = 60
VIDEO_DOWNLOAD_CHUNK_SIZE_BYTES = 1024 * 1024
FFMPEG_AUDIO_EXTRACTION_TIMEOUT_SECONDS = 120


def cleanup_temp_directory(job_id: str) -> None:
    project_root = resolve_project_root()
    temp_root = (project_root / "storage" / "temp").resolve()
    temp_directory = (temp_root / job_id).resolve()

    if temp_directory == temp_root or temp_root not in temp_directory.parents:
        logger.warning(
            "(%s) 임시 파일 정리 경로가 허용 범위를 벗어나 건너뜁니다: %s",
            job_id,
            temp_directory,
        )
        return

    if not temp_directory.exists():
        logger.info("(%s) 정리할 임시 파일이 없습니다: %s", job_id, temp_directory)
        return

    shutil.rmtree(temp_directory, ignore_errors=True)
    logger.info("(%s) 임시 파일 정리 완료: %s", job_id, temp_directory)


def resolve_video_path(video_path: str) -> Path | None:
    input_path = Path(video_path)
    candidate_paths = [
        input_path,
        Path.cwd() / input_path,
        Path.cwd().parent / input_path,
    ]

    if input_path.is_absolute():
        candidate_paths.insert(0, input_path)

    for candidate_path in candidate_paths:
        normalized_path = candidate_path.resolve()

        if normalized_path.exists() and normalized_path.is_file():
            try:
                validate_local_video_path(normalized_path)
            except ValueError as exception:
                logger.warning(
                    "허용된 스토리지 경로 밖의 videoPath라 거부합니다: %s",
                    exception,
                )
                return None

            return normalized_path

    return None


def resolve_analysis_engine_max_video_size_bytes() -> int:
    return get_settings().max_video_size_bytes


def resolve_or_download_video_path(
    job_id: str,
    video_path: str,
    video_download_url: str | None,
) -> Path | None:
    """Prefer a validated presigned URL and fall back to the validated local path."""
    if video_download_url:
        downloaded_path = download_video_from_url(job_id, video_download_url, video_path)

        if downloaded_path is not None:
            return downloaded_path

        logger.warning(
            "(%s) MinIO 다운로드 URL에서 영상을 내려받지 못해 로컬 경로로 폴백합니다.",
            job_id,
        )

    return resolve_video_path(video_path)


def download_video_from_url(
    job_id: str,
    video_download_url: str,
    original_video_path: str,
) -> Path | None:
    download_path: Path | None = None
    response = None
    try:
        validate_video_download_url(video_download_url)

        project_root = resolve_project_root()
        download_directory = project_root / "storage" / "temp" / job_id / "download"
        download_directory.mkdir(parents=True, exist_ok=True)

        extension = Path(original_video_path).suffix or ".mp4"
        download_path = download_directory / f"original{extension}"
        max_size = resolve_analysis_engine_max_video_size_bytes()

        # Redirect following would bypass the validated host allowlist.
        response = requests.get(
            video_download_url,
            stream=True,
            timeout=VIDEO_DOWNLOAD_TIMEOUT_SECONDS,
            allow_redirects=False,
        )

        if 300 <= response.status_code < 400:
            raise ValueError(
                f"videoDownloadUrl returned a redirect ({response.status_code}), which is not allowed."
            )

        response.raise_for_status()

        content_length = response.headers.get("content-length")
        if content_length is not None and int(content_length) > max_size:
            raise ValueError(
                "Downloaded video exceeds ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB "
                f"({content_length} bytes > {max_size} bytes)."
            )

        downloaded_size = 0
        with open(download_path, "wb") as video_file:
            for chunk in response.iter_content(chunk_size=VIDEO_DOWNLOAD_CHUNK_SIZE_BYTES):
                if not chunk:
                    continue

                downloaded_size += len(chunk)
                if downloaded_size > max_size:
                    raise ValueError(
                        "Downloaded video exceeds ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB "
                        f"({downloaded_size} bytes > {max_size} bytes)."
                    )
                video_file.write(chunk)

        if downloaded_size == 0:
            raise ValueError("Downloaded video is empty.")

        return download_path.resolve()

    except Exception as exception:
        if download_path is not None:
            download_path.unlink(missing_ok=True)
        logger.warning("(%s) MinIO 다운로드 URL 요청 실패: %s", job_id, exception)
        return None
    finally:
        if response is not None:
            response.close()


def create_mediapipe_image_from_frame_path(frame_path: str) -> mp.Image | None:
    image = cv2.imread(frame_path)

    if image is None:
        return None

    rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    return mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_image)


def preload_mediapipe_images(
    sampled_frames: List[Dict[str, Any]],
) -> List[mp.Image | None]:
    images: List[mp.Image | None] = []

    for frame_info in sampled_frames:
        frame_path = frame_info.get("framePath")
        images.append(
            create_mediapipe_image_from_frame_path(frame_path) if frame_path else None
        )

    return images


def extract_video_info(video_path: Path) -> Dict[str, Any]:
    file_size = video_path.stat().st_size
    capture = cv2.VideoCapture(str(video_path))

    try:
        if not capture.isOpened():
            return {
                "readable": False,
                "durationSec": 0,
                "fps": 0,
                "frameCount": 0,
                "width": 0,
                "height": 0,
                "fileSize": file_size,
            }

        fps = capture.get(cv2.CAP_PROP_FPS)
        frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
        duration_sec = round(frame_count / fps, 2) if fps and fps > 0 else 0

        return {
            "readable": True,
            "durationSec": duration_sec,
            "fps": round(fps, 2) if fps else 0,
            "frameCount": frame_count,
            "width": width,
            "height": height,
            "fileSize": file_size,
        }
    finally:
        capture.release()


def extract_sample_frames(
    job_id: str,
    video_path: Path,
    fps: float,
    frame_count: int,
) -> Dict[str, Any]:
    project_root = resolve_project_root()
    frame_directory = project_root / "storage" / "temp" / job_id / "frames"
    frame_directory.mkdir(parents=True, exist_ok=True)
    capture = cv2.VideoCapture(str(video_path))

    try:
        if not capture.isOpened():
            return {
                "savedCount": 0,
                "frameDirectory": str(frame_directory),
                "sampledFrames": [],
            }

        sampled_frames: List[Dict[str, Any]] = []
        for sequence, frame_index in enumerate(
            calculate_sample_frame_indexes(fps=fps, frame_count=frame_count),
            start=1,
        ):
            capture.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
            success, frame = capture.read()

            if not success:
                continue

            timestamp_sec = round(frame_index / fps, 2) if fps > 0 else 0
            frame_file_name = f"frame_{sequence:03d}_{timestamp_sec:.2f}s.jpg"
            frame_path = frame_directory / frame_file_name

            if not cv2.imwrite(str(frame_path), frame):
                continue

            sampled_frames.append(
                {
                    "sequence": sequence,
                    "frameIndex": frame_index,
                    "timestampSec": timestamp_sec,
                    "framePath": str(frame_path),
                }
            )

        return {
            "savedCount": len(sampled_frames),
            "frameDirectory": str(frame_directory),
            "sampledFrames": sampled_frames,
        }
    finally:
        capture.release()


def calculate_sample_frame_indexes(fps: float, frame_count: int) -> List[int]:
    if fps <= 0 or frame_count <= 0:
        return []

    interval_frame_count = max(int(fps * FRAME_EXTRACT_INTERVAL_SEC), 1)
    frame_indexes = list(range(0, frame_count, interval_frame_count))

    if len(frame_indexes) > MAX_EXTRACTED_FRAMES:
        step = len(frame_indexes) / MAX_EXTRACTED_FRAMES
        frame_indexes = [
            frame_indexes[int(index * step)] for index in range(MAX_EXTRACTED_FRAMES)
        ]

    return sorted(set(frame_indexes))


def extract_audio_from_video(job_id: str, video_path: Path) -> Dict[str, Any]:
    project_root = resolve_project_root()
    audio_directory = project_root / "storage" / "temp" / job_id / "audio"
    audio_directory.mkdir(parents=True, exist_ok=True)
    audio_path = audio_directory / "audio.wav"

    command = [
        imageio_ffmpeg.get_ffmpeg_exe(),
        "-y",
        "-i",
        str(video_path),
        "-vn",
        "-acodec",
        "pcm_s16le",
        "-ar",
        "16000",
        "-ac",
        "1",
        str(audio_path),
    ]

    try:
        completed_process = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
            timeout=FFMPEG_AUDIO_EXTRACTION_TIMEOUT_SECONDS,
        )

        if completed_process.returncode != 0:
            return _failed_audio_result(completed_process.stderr[-1000:])

        if not audio_path.exists() or audio_path.stat().st_size == 0:
            return _failed_audio_result("오디오 파일이 생성되지 않았습니다.")

        return {
            "success": True,
            "audioPath": str(audio_path),
            "fileSize": audio_path.stat().st_size,
            "sampleRate": 16000,
            "channelCount": 1,
            "codec": "pcm_s16le",
            "error": "",
        }
    except Exception as exception:
        return _failed_audio_result(str(exception))


def _failed_audio_result(error: str) -> Dict[str, Any]:
    return {
        "success": False,
        "audioPath": "",
        "fileSize": 0,
        "sampleRate": 16000,
        "channelCount": 1,
        "codec": "pcm_s16le",
        "error": error,
    }
