import unittest
from unittest.mock import Mock, patch

import numpy as np

from Back.app.services import face_analyzer


class FaceAnalyzerTests(unittest.TestCase):
    @patch.object(face_analyzer.mp, "Image")
    def test_transformation_matrix_adds_head_direction_metrics(self, image):
        image.create_from_file.return_value = Mock()
        landmarker = Mock()
        landmark = Mock(x=0.5, y=0.5, z=0.0)
        landmarker.detect.return_value = Mock(
            face_landmarks=[[landmark]],
            facial_transformation_matrixes=[np.eye(4)],
        )

        result = face_analyzer.analyze_face_from_frame("frame.jpg", landmarker)

        self.assertTrue(result["face_detected"])
        self.assertEqual(result["head_direction_score"], 100)
        self.assertEqual(result["yaw_degrees"], 0)

    @patch.object(face_analyzer.mp, "Image")
    def test_missing_face_remains_unavailable(self, image):
        image.create_from_file.return_value = Mock()
        landmarker = Mock()
        landmarker.detect.return_value = Mock(
            face_landmarks=[],
            facial_transformation_matrixes=[],
        )

        result = face_analyzer.analyze_face_from_frame("frame.jpg", landmarker)

        self.assertFalse(result["face_detected"])
        self.assertNotIn("head_direction_score", result)


if __name__ == "__main__":
    unittest.main()
