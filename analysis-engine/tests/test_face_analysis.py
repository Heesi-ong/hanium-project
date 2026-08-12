from contextlib import contextmanager
from types import SimpleNamespace

from app.core import model_registry
from app.services import face_analysis


def landmark(x: float, y: float):
    return SimpleNamespace(x=x, y=y)


def centered_face_landmarks():
    landmarks = [landmark(0.0, 0.0) for _ in range(478)]

    landmarks[face_analysis.LEFT_EYE_OUTER_INDEX] = landmark(0.3, 0.5)
    landmarks[face_analysis.LEFT_EYE_INNER_INDEX] = landmark(0.5, 0.5)
    landmarks[face_analysis.RIGHT_EYE_INNER_INDEX] = landmark(0.5, 0.5)
    landmarks[face_analysis.RIGHT_EYE_OUTER_INDEX] = landmark(0.7, 0.5)
    landmarks[face_analysis.NOSE_TIP_INDEX] = landmark(0.5, 0.6)

    landmarks[face_analysis.LEFT_EYE_TOP_INDEX] = landmark(0.4, 0.45)
    landmarks[face_analysis.LEFT_EYE_BOTTOM_INDEX] = landmark(0.4, 0.55)
    landmarks[face_analysis.RIGHT_EYE_TOP_INDEX] = landmark(0.6, 0.45)
    landmarks[face_analysis.RIGHT_EYE_BOTTOM_INDEX] = landmark(0.6, 0.55)

    landmarks[face_analysis.MOUTH_TOP_INDEX] = landmark(0.5, 0.45)
    landmarks[face_analysis.MOUTH_BOTTOM_INDEX] = landmark(0.5, 0.55)
    landmarks[face_analysis.MOUTH_LEFT_INDEX] = landmark(0.4, 0.5)
    landmarks[face_analysis.MOUTH_RIGHT_INDEX] = landmark(0.6, 0.5)

    landmarks[face_analysis.LEFT_IRIS_CENTER_INDEX] = landmark(0.4, 0.5)
    landmarks[face_analysis.RIGHT_IRIS_CENTER_INDEX] = landmark(0.6, 0.5)
    return landmarks


class FakeFaceLandmarker:
    def detect(self, image):
        return SimpleNamespace(face_landmarks=[centered_face_landmarks()])


def install_face_landmarker(monkeypatch):
    @contextmanager
    def face_landmarker_context():
        yield FakeFaceLandmarker()

    monkeypatch.setattr(
        model_registry,
        "face_landmarker_context",
        face_landmarker_context,
    )


def test_analyze_face_preserves_detected_and_missing_frame_contract(monkeypatch):
    install_face_landmarker(monkeypatch)
    frames = [
        {"sequence": 1, "timestampSec": 0.0},
        {"sequence": 2, "timestampSec": 1.0},
    ]

    # 이미지 목록이 프레임보다 짧아도 뒤쪽 프레임을 조용히 누락하지 않습니다.
    result = face_analysis.analyze_face_from_frames(frames, [object()])

    assert result["analysisMethod"] == "mediapipe_tasks_face_landmarker_iris_gaze_ratio"
    assert result["detectionRate"] == 0.5
    assert result["detectedFrameCount"] == 1
    assert result["totalFrameCount"] == 2
    assert result["gazeScore"] == 90
    assert result["averageFrameGazeScore"] == 100
    assert result["cameraGazeRatio"] == 1.0
    assert result["eyeContactLevel"] == "good"
    assert result["frameResults"][0] == {
        "sequence": 1,
        "timestampSec": 0.0,
        "faceDetected": True,
        "irisHorizontalRatio": 0.5,
        "irisVerticalRatio": 0.5,
        "gazingAtCamera": True,
        "gazeScore": 100,
        "mouthOpenness": 0.5,
        "eyeOpenness": 0.25,
        "landmarks": {
            "leftEyeOuter": {"x": 0.3, "y": 0.5},
            "rightEyeOuter": {"x": 0.7, "y": 0.5},
            "noseTip": {"x": 0.5, "y": 0.6},
        },
    }
    assert result["frameResults"][1] == {
        "sequence": 2,
        "timestampSec": 1.0,
        "faceDetected": False,
        "irisHorizontalRatio": None,
        "irisVerticalRatio": None,
        "gazingAtCamera": False,
        "gazeScore": 0,
        "mouthOpenness": 0,
        "eyeOpenness": 0,
    }


def test_analyze_emotion_preserves_detected_and_unknown_aggregation_contract():
    face_result = {
        "frameResults": [
            {
                "sequence": 1,
                "timestampSec": 0.0,
                "faceDetected": True,
                "mouthOpenness": 0.5,
                "eyeOpenness": 0.25,
                "gazeScore": 100,
            },
            {
                "sequence": 2,
                "timestampSec": 1.0,
                "faceDetected": False,
                "mouthOpenness": 0,
                "eyeOpenness": 0,
                "gazeScore": 0,
            },
        ]
    }

    result = face_analysis.analyze_emotion_from_face_result(face_result)

    assert (
        result["analysisMethod"] == "mediapipe_tasks_face_landmarker_expression_based"
    )
    assert result["expressionScore"] == 52
    assert result["expressionRawScore"] == 50
    assert result["expressionVarietyScore"] == 60
    assert result["detectionRate"] == 0.5
    assert result["detectedFrameCount"] == 1
    assert result["totalFrameCount"] == 2
    assert result["emotionState"]["dominantEmotion"] == "speaking"
    assert result["emotionState"]["emotionCounts"] == {
        "neutral": 0,
        "engaged": 0,
        "speaking": 1,
        "low_energy": 0,
        "unknown": 1,
    }
    assert [frame["emotionLabel"] for frame in result["frameResults"]] == [
        "speaking",
        "unknown",
    ]
