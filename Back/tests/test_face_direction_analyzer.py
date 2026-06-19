import math
import unittest

import numpy as np

from Back.app.services.face_direction_analyzer import (
    calculate_head_direction_score,
    calculate_legacy_gaze_score,
    extract_head_direction,
)


def rotation_matrix(yaw=0, pitch=0, roll=0):
    yaw = math.radians(yaw)
    pitch = math.radians(pitch)
    roll = math.radians(roll)
    rotation_x = np.array([
        [1, 0, 0],
        [0, math.cos(pitch), -math.sin(pitch)],
        [0, math.sin(pitch), math.cos(pitch)],
    ])
    rotation_y = np.array([
        [math.cos(yaw), 0, math.sin(yaw)],
        [0, 1, 0],
        [-math.sin(yaw), 0, math.cos(yaw)],
    ])
    rotation_z = np.array([
        [math.cos(roll), -math.sin(roll), 0],
        [math.sin(roll), math.cos(roll), 0],
        [0, 0, 1],
    ])
    matrix = np.eye(4)
    matrix[:3, :3] = rotation_z @ rotation_y @ rotation_x
    return matrix


class FaceDirectionAnalyzerTests(unittest.TestCase):
    def test_extracts_neutral_direction(self):
        result = extract_head_direction(rotation_matrix())

        self.assertEqual(result["yaw_degrees"], 0)
        self.assertEqual(result["pitch_degrees"], 0)
        self.assertEqual(result["roll_degrees"], 0)
        self.assertEqual(result["head_direction_score"], 100)

    def test_extracts_rotated_direction(self):
        result = extract_head_direction(rotation_matrix(yaw=20, pitch=15, roll=5))

        self.assertAlmostEqual(result["yaw_degrees"], 20, delta=0.01)
        self.assertAlmostEqual(result["pitch_degrees"], 15, delta=0.01)
        self.assertAlmostEqual(result["roll_degrees"], 5, delta=0.01)
        self.assertEqual(result["head_direction_score"], 70)

    def test_rejects_invalid_matrix(self):
        self.assertIsNone(extract_head_direction([[1, 0], [0, 1]]))

    def test_head_direction_score_uses_all_axes(self):
        self.assertEqual(calculate_head_direction_score(0, 0, 0), 100)
        self.assertEqual(calculate_head_direction_score(20, 0, 0), 70)
        self.assertEqual(calculate_head_direction_score(0, 0, 30), 40)

    def test_legacy_gaze_score_keeps_existing_thresholds(self):
        landmarks = [
            {"id": 1, "x": 0.51},
            {"id": 33, "x": 0.4},
            {"id": 263, "x": 0.6},
        ]

        self.assertEqual(calculate_legacy_gaze_score(landmarks), 100)


if __name__ == "__main__":
    unittest.main()
