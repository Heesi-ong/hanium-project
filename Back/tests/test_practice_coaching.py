import tempfile
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from Back.app.services import practice_coaching, practice_contexts


class PracticeCoachingTests(unittest.TestCase):
    def test_context_is_isolated_by_user(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.object(practice_contexts, "PRACTICE_CONTEXT_DIR", Path(temp_dir)):
                practice_coaching.save_practice_context(
                    "job-1",
                    7,
                    {
                        "purpose": "project",
                        "audience": "심사위원",
                        "core_message": "가치",
                        "target_minutes": 5,
                        "series_name": "최종 발표",
                    },
                )
                self.assertEqual(practice_coaching.load_practice_context("job-1", 7)["audience"], "심사위원")
                self.assertTrue(practice_coaching.load_practice_context("job-1", 7)["series_id"])
                self.assertIsNone(practice_coaching.load_practice_context("job-1", 8))

    def test_coaching_returns_three_actions_and_purpose_questions(self):
        result = {
            "data": {
                "video_info": {"duration_seconds": 300},
                "audio_result": {
                    "text": "오늘 프로젝트를 발표합니다. 첫째 문제를 설명합니다. 따라서 결과입니다.",
                    "segments": [
                        {"start": 0, "end": 2, "text": "오늘 프로젝트를 발표합니다."},
                        {"start": 120, "end": 124, "text": "첫째 문제를 설명합니다."},
                        {"start": 280, "end": 285, "text": "따라서 결과입니다."},
                    ],
                },
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
        self.assertEqual(coaching["content_analysis"]["method"], "heuristic")
        self.assertEqual(coaching["content_analysis"]["structure"][0]["start"], 0)
        self.assertIn("프로젝트", coaching["expected_questions"][0])

    def test_missing_audio_is_not_scored_as_bad_content(self):
        coaching = practice_coaching.build_practice_coaching(
            {"data": {"score_result": {}, "audio_result": {}, "summary_result": {}}},
            {"purpose": "class", "core_message": ""},
        )
        self.assertFalse(coaching["content_analysis"]["available"])
        self.assertEqual(coaching["confidence"]["audio"], "제한적")

    def test_growth_compares_only_same_purpose_and_series(self):
        contexts = {
            "one": {"purpose": "project", "series_id": "series-a"},
            "other": {"purpose": "class", "series_id": "series-a"},
            "two": {"purpose": "project", "series_id": "series-a"},
        }
        growth = [
            {"result_id": "one", "total_score": 60},
            {"result_id": "other", "total_score": 90},
            {"result_id": "two", "total_score": 70},
        ]
        with patch.object(practice_coaching, "load_practice_context", side_effect=lambda result_id, _user: contexts[result_id]):
            result = practice_coaching.enrich_growth(growth, 7)

        self.assertIsNone(result[1]["score_change"])
        self.assertEqual(result[2]["score_change"], 10)

    def test_previous_same_series_is_found_when_growth_input_is_newest_first(self):
        now = datetime.now()
        growth = [
            {"result_id": "current", "total_score": 70, "created_at": now},
            {"result_id": "previous", "total_score": 60, "created_at": now - timedelta(minutes=1)},
        ]
        contexts = {
            "current": {"purpose": "project", "series_id": "series-a"},
            "previous": {"purpose": "project", "series_id": "series-a"},
        }
        with patch.object(practice_contexts, "load_practice_context", side_effect=lambda result_id, _user: contexts[result_id]):
            previous = practice_coaching.find_previous_same_series(growth, 7, "current", contexts["current"])

        self.assertEqual(previous["result_id"], "previous")

    def test_database_decimal_score_is_used_for_comparison(self):
        coaching = practice_coaching.build_practice_coaching(
            {"data": {"summary_result": {"total_score": 70}, "score_result": {}, "audio_result": {}}},
            {"purpose": "project"},
            {"total_score": Decimal("65.00")},
        )

        self.assertEqual(coaching["comparison"]["score_change"], 5)

    def test_orphan_contexts_are_detected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "active.json").write_text("{}", encoding="utf-8")
            (root / "orphan.json").write_text("{}", encoding="utf-8")
            with patch.object(practice_contexts, "PRACTICE_CONTEXT_DIR", root):
                self.assertEqual(practice_coaching.list_orphan_practice_contexts({"active"}), ["orphan"])


if __name__ == "__main__":
    unittest.main()
