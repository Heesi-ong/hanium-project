import unittest

from Back.app.services.timeline_analyzer import analyze_timeline_scores


class TimelineAnalyzerTests(unittest.TestCase):
    def test_unavailable_visual_frame_is_not_scored_zero(self):
        result = analyze_timeline_scores(
            {"duration_seconds": 1},
            {"frames": ["frame.jpg"]},
            [{"pose_detected": False}],
            [{"face_detected": False}],
        )

        frame = result["timeline"][0]
        self.assertIsNone(frame["frame_score"])
        self.assertIsNone(frame["pose_score"])
        self.assertIsNone(frame["gaze_score"])

    def test_too_few_detected_frames_do_not_create_timeline_performance_score(self):
        pose = {"pose_detected": True, "landmarks": [{"id": 11, "y": 0.4}, {"id": 12, "y": 0.4}]}
        result = analyze_timeline_scores(
            {"duration_seconds": 10},
            {"frames": [f"{index}.jpg" for index in range(10)]},
            [pose] + [{"pose_detected": False}] * 9,
            [{"face_detected": False}] * 10,
        )

        self.assertIsNone(result["timeline"][0]["shoulder_score"])
        self.assertIsNone(result["timeline"][0]["head_direction_score"])
        self.assertIsNone(result["timeline"][0]["frame_score"])

    def test_head_direction_measurements_are_exposed_without_replacing_gaze_score(self):
        face = {
            "face_detected": True,
            "landmarks": [
                {"id": 1, "x": 0.5},
                {"id": 33, "x": 0.4},
                {"id": 263, "x": 0.6},
            ],
            "head_direction_score": 70,
            "yaw_degrees": 20,
            "pitch_degrees": 5,
            "roll_degrees": 0,
        }
        result = analyze_timeline_scores(
            {"duration_seconds": 3},
            {"frames": ["1.jpg", "2.jpg", "3.jpg"]},
            [{"pose_detected": False}] * 3,
            [face] * 3,
        )

        frame = result["timeline"][0]
        self.assertEqual(frame["gaze_score"], 100)
        self.assertEqual(frame["head_direction_score"], 70)
        self.assertEqual(frame["yaw_degrees"], 20)


if __name__ == "__main__":
    unittest.main()
