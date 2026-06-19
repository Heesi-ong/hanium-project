"""영상 길이, 해상도, FPS, 추출 프레임 계획 같은 기본 메타데이터를 검증한다."""

import math

import cv2

from ..config import (
    ANALYSIS_FRAME_INTERVAL_SECONDS,
    MAX_EXTRACTED_FRAMES,
    MAX_VIDEO_DURATION_SECONDS,
    MAX_VIDEO_FPS,
    MAX_VIDEO_FRAMES,
    MAX_VIDEO_HEIGHT,
    MAX_VIDEO_WIDTH,
)


def get_video_info(video_path: str):
    cap = cv2.VideoCapture(video_path)

    if not cap.isOpened():
        return {
            "error": "video open failed"
        }

    fps = cap.get(cv2.CAP_PROP_FPS)
    frame_count = cap.get(cv2.CAP_PROP_FRAME_COUNT)
    width = cap.get(cv2.CAP_PROP_FRAME_WIDTH)
    height = cap.get(cv2.CAP_PROP_FRAME_HEIGHT)

    duration = frame_count / fps if fps > 0 else 0

    cap.release()

    return {
        "fps": fps,
        "frame_count": frame_count,
        "width": width,
        "height": height,
        "duration_seconds": duration
    }


def validate_video_info(video_info, interval_seconds=None):
    interval_seconds = interval_seconds or ANALYSIS_FRAME_INTERVAL_SECONDS
    fields = ("fps", "frame_count", "width", "height", "duration_seconds")
    if video_info.get("error") or any(
        not isinstance(video_info.get(field), (int, float))
        or not math.isfinite(video_info[field])
        or video_info[field] <= 0
        for field in fields
    ):
        raise ValueError("재생 가능한 정상 영상 파일을 선택해주세요.")

    limits = (
        ("duration_seconds", MAX_VIDEO_DURATION_SECONDS, "영상 길이"),
        ("width", MAX_VIDEO_WIDTH, "영상 너비"),
        ("height", MAX_VIDEO_HEIGHT, "영상 높이"),
        ("fps", MAX_VIDEO_FPS, "영상 FPS"),
        ("frame_count", MAX_VIDEO_FRAMES, "영상 총 프레임 수"),
    )
    for field, maximum, label in limits:
        if video_info[field] > maximum:
            raise ValueError(f"{label}가 허용 기준({maximum})을 초과합니다.")

    expected_frames = math.ceil(video_info["duration_seconds"] / interval_seconds)
    if expected_frames > MAX_EXTRACTED_FRAMES:
        raise ValueError(f"예상 추출 프레임 수가 허용 기준({MAX_EXTRACTED_FRAMES})을 초과합니다.")
    return {**video_info, "expected_extracted_frames": expected_frames}
