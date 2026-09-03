import base64

import cv2
import numpy as np

from app.services import frame_overlay


def _write_frame(path, width=1280, height=720):
    image = np.full((height, width, 3), 120, dtype=np.uint8)
    assert cv2.imwrite(str(path), image)
    return str(path)


def _landmark(x, y, visibility=1.0):
    return {"x": x, "y": y, "visibility": visibility, "presence": 1.0}


def _pose_frame(sequence, *, detected=True):
    if not detected:
        return {"sequence": sequence, "timestampSec": sequence * 1.0, "poseDetected": False}

    return {
        "sequence": sequence,
        "timestampSec": sequence * 1.0,
        "poseDetected": True,
        "leftShoulder": _landmark(0.40, 0.30),
        "rightShoulder": _landmark(0.60, 0.31),
        "leftElbow": _landmark(0.35, 0.45),
        "rightElbow": _landmark(0.65, 0.45),
        "leftWrist": _landmark(0.33, 0.60),
        "rightWrist": _landmark(0.67, 0.60),
    }


def test_build_frame_overlays_returns_decodable_jpeg_per_frame(tmp_path):
    frame_path = _write_frame(tmp_path / "frame_001.jpg")
    sampled_frames = [{"sequence": 1, "timestampSec": 1.0, "framePath": frame_path}]

    overlays = frame_overlay.build_frame_overlays(
        sampled_frames,
        [_pose_frame(1)],
        [{"sequence": 1, "gestureDetected": True}],
    )

    assert len(overlays) == 1
    overlay = overlays[0]
    assert overlay["sequence"] == 1
    assert overlay["poseDetected"] is True
    assert overlay["gestureDetected"] is True
    assert overlay["imageMimeType"] == "image/jpeg"

    decoded = cv2.imdecode(
        np.frombuffer(base64.b64decode(overlay["imageBase64"]), dtype=np.uint8),
        cv2.IMREAD_COLOR,
    )
    assert decoded is not None
    # 가로 640px 상한으로 축소됩니다.
    assert decoded.shape[1] == frame_overlay.OVERLAY_MAX_WIDTH


def test_build_frame_overlays_marks_undetected_pose(tmp_path):
    frame_path = _write_frame(tmp_path / "frame_002.jpg")
    sampled_frames = [{"sequence": 2, "timestampSec": 2.0, "framePath": frame_path}]

    overlays = frame_overlay.build_frame_overlays(
        sampled_frames, [_pose_frame(2, detected=False)], []
    )

    assert len(overlays) == 1
    assert overlays[0]["poseDetected"] is False
    assert overlays[0]["gestureDetected"] is False


def test_build_frame_overlays_skips_missing_frame_file(tmp_path):
    sampled_frames = [
        {"sequence": 1, "timestampSec": 1.0, "framePath": str(tmp_path / "nope.jpg")}
    ]

    overlays = frame_overlay.build_frame_overlays(sampled_frames, [_pose_frame(1)], [])

    assert overlays == []
