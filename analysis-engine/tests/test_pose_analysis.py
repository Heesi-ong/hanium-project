from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from app.core import model_registry
from app.services import pose_analysis


def landmark(x, y, visibility=1.0, presence=1.0):
    return SimpleNamespace(
        x=x,
        y=y,
        visibility=visibility,
        presence=presence,
    )


def pose_landmarks():
    landmarks = [landmark(0.0, 0.0) for _ in range(17)]
    landmarks[pose_analysis.LEFT_SHOULDER_INDEX] = landmark(0.4, 0.4)
    landmarks[pose_analysis.RIGHT_SHOULDER_INDEX] = landmark(0.6, 0.42)
    landmarks[pose_analysis.LEFT_ELBOW_INDEX] = landmark(0.35, 0.5)
    landmarks[pose_analysis.RIGHT_ELBOW_INDEX] = landmark(0.65, 0.5)
    landmarks[pose_analysis.LEFT_WRIST_INDEX] = landmark(0.2, 0.45)
    landmarks[pose_analysis.RIGHT_WRIST_INDEX] = landmark(0.8, 0.45)
    return landmarks


class FakePoseLandmarker:
    def detect(self, image):
        return SimpleNamespace(pose_landmarks=[pose_landmarks()])


def install_pose_landmarker(monkeypatch):
    @contextmanager
    def pose_landmarker_context():
        yield FakePoseLandmarker()

    monkeypatch.setattr(
        model_registry,
        "pose_landmarker_context",
        pose_landmarker_context,
    )


def test_analyze_pose_preserves_detected_and_missing_frame_contract(monkeypatch):
    install_pose_landmarker(monkeypatch)
    frames = [
        {"sequence": 1, "timestampSec": 0.0},
        {"sequence": 2, "timestampSec": 1.0},
    ]

    # 이미지 목록이 프레임보다 짧아도 뒤쪽 프레임을 조용히 누락하지 않습니다.
    result = pose_analysis.analyze_pose_from_frames(frames, [object()])

    assert result["analysisMethod"] == "mediapipe_tasks_pose_landmarker"
    assert result["detectionRate"] == 0.5
    assert result["detectedFrameCount"] == 1
    assert result["totalFrameCount"] == 2
    assert result["postureScore"] == 80
    assert result["shoulderBalanceScore"] == 100
    assert result["averageShoulderDiff"] == 0.02
    assert result["frameResults"][0]["leftWrist"] == {
        "x": 0.2,
        "y": 0.45,
        "visibility": 1.0,
        "presence": 1.0,
    }
    assert result["frameResults"][1] == {
        "sequence": 2,
        "timestampSec": 1.0,
        "poseDetected": False,
        "shoulderDiff": None,
        "shoulderBalanceScore": 0,
    }


def test_analyze_gesture_preserves_visibility_movement_and_score_contract():
    shoulder = {"x": 0.4, "y": 0.4, "visibility": 1.0}
    elbow = {"x": 0.35, "y": 0.5, "visibility": 1.0}
    hidden = {"x": 0.6, "y": 0.6, "visibility": 0.0}

    pose_result = {
        "frameResults": [
            {
                "sequence": 1,
                "timestampSec": 0.0,
                "poseDetected": True,
                "leftShoulder": shoulder,
                "rightShoulder": shoulder,
                "leftElbow": elbow,
                "rightElbow": hidden,
                "leftWrist": {"x": 0.2, "y": 0.45, "visibility": 1.0},
                "rightWrist": hidden,
            },
            {
                "sequence": 2,
                "timestampSec": 1.0,
                "poseDetected": True,
                "leftShoulder": shoulder,
                "rightShoulder": shoulder,
                "leftElbow": elbow,
                "rightElbow": hidden,
                "leftWrist": {"x": 0.25, "y": 0.44, "visibility": 1.0},
                "rightWrist": hidden,
            },
        ]
    }

    result = pose_analysis.analyze_gesture_from_pose_result(pose_result)

    assert result["gestureRate"] == 1.0
    assert result["gestureFrameCount"] == 2
    assert result["totalFrameCount"] == 2
    assert result["handVisibilityRate"] == 0.5
    assert result["averageWristMovement"] == pytest.approx(0.051, abs=0.0001)
    assert result["gestureVarietyScore"] == 40
    assert result["handVisibilityScore"] == 50
    assert result["gestureMovementScore"] == 100
    assert result["gestureScore"] == 60
    assert result["frameResults"][0]["leftWristMovement"] is None
    assert result["frameResults"][1]["leftWristMovement"] == pytest.approx(
        0.051,
        abs=0.0001,
    )
