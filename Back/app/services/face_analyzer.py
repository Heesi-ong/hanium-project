"""MediaPipe Face Landmarker 결과에서 얼굴 감지와 얼굴 방향 원천 데이터를 추출한다."""

import os

import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

from ..config import MODEL_DIR
from .face_direction_analyzer import extract_head_direction

MODEL_PATH = MODEL_DIR / "face_landmarker.task"
MIN_FACE_DETECTION_CONFIDENCE = 0.5
MIN_FACE_PRESENCE_CONFIDENCE = 0.5


def create_face_landmarker():
    if not os.path.exists(MODEL_PATH):
        raise FileNotFoundError(f"face model file not found: {MODEL_PATH}")

    base_options = python.BaseOptions(
        model_asset_path=str(MODEL_PATH)
    )

    options = vision.FaceLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.IMAGE,
        num_faces=1,
        min_face_detection_confidence=MIN_FACE_DETECTION_CONFIDENCE,
        min_face_presence_confidence=MIN_FACE_PRESENCE_CONFIDENCE,
        output_facial_transformation_matrixes=True,
    )

    return vision.FaceLandmarker.create_from_options(options)


def analyze_face_from_frame(frame_path: str, landmarker):
    image = mp.Image.create_from_file(frame_path)
    result = landmarker.detect(image)

    if not result.face_landmarks:
        return {
            "frame": frame_path,
            "face_detected": False
        }

    landmarks = []

    for idx, lm in enumerate(result.face_landmarks[0]):
        landmarks.append({
            "id": idx,
            "x": lm.x,
            "y": lm.y,
            "z": lm.z
        })

    face_result = {
        "frame": frame_path,
        "face_detected": True,
        "landmark_count": len(landmarks),
        "landmarks": landmarks
    }
    if result.facial_transformation_matrixes:
        direction = extract_head_direction(result.facial_transformation_matrixes[0])
        if direction:
            face_result.update(direction)

    return face_result
