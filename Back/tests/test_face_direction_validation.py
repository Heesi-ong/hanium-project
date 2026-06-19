import unittest

from Back.app.services.face_direction_validation import build_face_direction_comparison


class FaceDirectionValidationTest(unittest.TestCase):
    def test_builds_comparison_without_changing_scores(self):
        result = build_face_direction_comparison(
            {
                "result_id": "result-1",
                "data": {
                    "analysis_metadata": {"algorithm_version": "2026.06.3"},
                    "face_results": [
                        {"face_detected": True},
                        {"face_detected": True},
                        {"face_detected": False},
                    ],
                    "timeline_result": {
                        "timeline": [
                            {
                                "time_sec": 0,
                                "gaze_score": 100,
                                "head_direction_score": 100,
                                "yaw_degrees": 1,
                                "pitch_degrees": 2,
                                "roll_degrees": 3,
                            },
                            {
                                "time_sec": 1,
                                "gaze_score": 100,
                                "head_direction_score": 40,
                                "yaw_degrees": 30,
                                "pitch_degrees": 2,
                                "roll_degrees": 3,
                            },
                            {
                                "time_sec": 2,
                                "gaze_score": None,
                                "head_direction_score": None,
                            },
                        ]
                    },
                },
            }
        )

        self.assertEqual(result["algorithm_version"], "2026.06.3")
        self.assertEqual(result["summary"]["timeline_frame_count"], 3)
        self.assertEqual(result["summary"]["comparable_frame_count"], 2)
        self.assertEqual(result["summary"]["exact_score_agreement_rate"], 50)
        self.assertEqual(result["summary"]["mean_absolute_score_difference"], 30)
        self.assertEqual(result["rows"][1]["gaze_score"], 100)
        self.assertEqual(result["rows"][1]["head_direction_score"], 40)

    def test_empty_result_reports_unavailable_comparison(self):
        result = build_face_direction_comparison({"result_id": "empty", "data": {}})

        self.assertEqual(result["summary"]["timeline_frame_count"], 0)
        self.assertEqual(result["summary"]["comparable_frame_count"], 0)
        self.assertIsNone(result["summary"]["exact_score_agreement_rate"])
        self.assertIsNone(result["summary"]["mean_absolute_score_difference"])


if __name__ == "__main__":
    unittest.main()
