import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from Back.app.services import practice_coaching


class PracticeCoachingTests(unittest.TestCase):
    def test_context_is_isolated_by_user(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.object(practice_coaching, "PRACTICE_CONTEXT_DIR", Path(temp_dir)):
                practice_coaching.save_practice_context(
                    "job-1",
                    7,
                    {"purpose": "project", "audience": "심사위원", "core_message": "가치", "target_minutes": 5},
                )
                self.assertEqual(practice_coaching.load_practice_context("job-1", 7)["audience"], "심사위원")
                self.assertIsNone(practice_coaching.load_practice_context("job-1", 8))

    def test_coaching_returns_three_actions_and_purpose_questions(self):
        result = {
            "data": {
                "video_info": {"duration_seconds": 300},
                "audio_result": {"text": "오늘 프로젝트를 발표합니다. 첫째 문제를 설명합니다. 따라서 결과입니다."},
                "score_result": {
                    "total_score": 70,
                    "gaze_score": 50,
                    "shoulder_balance_score": 60,
                    "speech_speed_score": 80,
                    "gesture_score": 65,
                    "volume_score": 75,
                    "pose_detection_rate": 90,
                    "face_detection_rate": 90,
                    "audio_analysis_available": True,
                },
                "filler_result": {"filler_score": 90},
                "summary_result": {"total_score": 70},
            }
        }
        coaching = practice_coaching.build_practice_coaching(
            result,
            {
                "purpose": "project",
                "audience": "심사위원",
                "core_message": "프로젝트 가치",
                "target_minutes": 10,
                "series_name": "최종 발표",
            },
            {"total_score": 65},
        )
        self.assertEqual(len(coaching["improvement_plan"]), 3)
        self.assertEqual(coaching["comparison"]["score_change"], 5)
        self.assertTrue(coaching["content_analysis"]["available"])
        self.assertIn("프로젝트", coaching["expected_questions"][0])

    def test_missing_audio_is_not_scored_as_bad_content(self):
        coaching = practice_coaching.build_practice_coaching(
            {"data": {"score_result": {}, "audio_result": {}, "summary_result": {}}},
            {"purpose": "class", "core_message": ""},
        )
        self.assertFalse(coaching["content_analysis"]["available"])
        self.assertEqual(coaching["confidence"]["audio"], "제한적")


if __name__ == "__main__":
    unittest.main()
