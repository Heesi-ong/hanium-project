"""얼굴 랜드마크와 transformation matrix를 시선 점수 및 yaw/pitch/roll 지표로 변환한다."""

import math

import numpy as np


def calculate_legacy_gaze_score(landmarks):
    nose = next((lm for lm in landmarks if lm["id"] == 1), None)
    left_eye = next((lm for lm in landmarks if lm["id"] == 33), None)
    right_eye = next((lm for lm in landmarks if lm["id"] == 263), None)

    if not nose or not left_eye or not right_eye:
        return None

    eye_center_x = (left_eye["x"] + right_eye["x"]) / 2
    diff = abs(nose["x"] - eye_center_x)

    if diff < 0.02:
        return 100
    if diff < 0.05:
        return 70
    return 40


def extract_head_direction(transformation_matrix):
    if transformation_matrix is None:
        return None

    matrix = np.asarray(transformation_matrix, dtype=float)
    if matrix.shape != (4, 4) or not np.isfinite(matrix).all():
        return None

    rotation = matrix[:3, :3]
    yaw_radians = math.asin(max(-1.0, min(1.0, -rotation[2, 0])))
    pitch_radians = math.atan2(rotation[2, 1], rotation[2, 2])
    roll_radians = math.atan2(rotation[1, 0], rotation[0, 0])

    yaw = round(math.degrees(yaw_radians), 2)
    pitch = round(math.degrees(pitch_radians), 2)
    roll = round(math.degrees(roll_radians), 2)
    return {
        "yaw_degrees": yaw,
        "pitch_degrees": pitch,
        "roll_degrees": roll,
        "head_direction_score": calculate_head_direction_score(yaw, pitch, roll),
    }


def calculate_head_direction_score(yaw, pitch, roll):
    absolute_yaw = abs(yaw)
    absolute_pitch = abs(pitch)
    absolute_roll = abs(roll)

    if absolute_yaw <= 12 and absolute_pitch <= 10 and absolute_roll <= 10:
        return 100
    if absolute_yaw <= 25 and absolute_pitch <= 20 and absolute_roll <= 20:
        return 70
    return 40
