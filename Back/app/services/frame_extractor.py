import os
import re
from uuid import uuid4

import cv2

from ..config import FRAME_DIR


def extract_frames(video_path: str, interval_sec: int = 1, output_id: str | None = None):
    cap = cv2.VideoCapture(video_path)

    if not cap.isOpened():
        return {
            "error": "video open failed"
        }

    fps = cap.get(cv2.CAP_PROP_FPS)
    frame_interval = max(1, int(fps * interval_sec))

    directory_name = output_id or str(uuid4())
    if not re.fullmatch(r"[A-Za-z0-9_-]+", directory_name):
        cap.release()
        raise ValueError("invalid frame output id")

    output_dir = FRAME_DIR / directory_name
    os.makedirs(output_dir, exist_ok=True)

    saved_frames = []
    frame_index = 0
    saved_count = 0

    while True:
        success, frame = cap.read()

        if not success:
            break

        if frame_index % frame_interval == 0:
            filename = f"frame_{saved_count}.jpg"
            frame_path = output_dir / filename

            if not cv2.imwrite(str(frame_path), frame):
                cap.release()
                raise RuntimeError(f"frame save failed: {frame_path}")

            saved_frames.append(str(frame_path))
            saved_count += 1

        frame_index += 1

    cap.release()

    return {
        "output_dir": str(output_dir),
        "saved_count": saved_count,
        "frames": saved_frames
    }
