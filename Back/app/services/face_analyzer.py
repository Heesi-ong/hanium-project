import os

import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

from ..config import MODEL_DIR

MODEL_PATH = MODEL_DIR / "face_landmarker.task"


def create_face_landmarker():
    if not os.path.exists(MODEL_PATH):
        raise FileNotFoundError(f"face model file not found: {MODEL_PATH}")

    base_options = python.BaseOptions(
        model_asset_path=str(MODEL_PATH)
    )

    options = vision.FaceLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.IMAGE,
        num_faces=1
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

    return {
        "frame": frame_path,
        "face_detected": True,
        "landmark_count": len(landmarks),
        "landmarks": landmarks
    }
