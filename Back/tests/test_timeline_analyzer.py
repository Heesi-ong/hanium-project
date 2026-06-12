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


if __name__ == "__main__":
    unittest.main()
