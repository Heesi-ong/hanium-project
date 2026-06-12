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
        self.assertIsNone(result["timeline"][0]["frame_score"])


if __name__ == "__main__":
    unittest.main()
