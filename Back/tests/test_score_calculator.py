import unittest

from Back.app.services.score_calculator import calculate_basic_score


class ScoreCalculatorTest(unittest.TestCase):
    def test_unavailable_audio_scores_are_excluded(self):
        result = calculate_basic_score(
            video_info={"duration_seconds": 10},
            frame_result={"saved_count": 0},
            pose_results=[],
            face_results=[],
            audio_result={
                "text": "",
                "segments": [],
                "speech_speed_wpm": 0,
                "silence_count": 0,
                "total_silence_time": 0,
                "filler_score": None
            },
            gesture_result={"gesture_score": 0},
            volume_result={"volume_score": 0}
        )

        self.assertFalse(result["audio_analysis_available"])
        self.assertIsNone(result["speech_speed_score"])
        self.assertIsNone(result["silence_score"])
        self.assertIsNone(result["filler_score"])
        self.assertEqual(result["total_score"], 0)

    def test_available_audio_scores_are_included(self):
        result = calculate_basic_score(
            video_info={"duration_seconds": 10},
            frame_result={"saved_count": 0},
            pose_results=[],
            face_results=[],
            audio_result={
                "text": "발표 내용",
                "segments": [{"start": 0, "end": 10}],
                "speech_speed_wpm": 120,
                "silence_count": 0,
                "total_silence_time": 0,
                "filler_score": 100
            },
            gesture_result={"gesture_score": 0},
            volume_result={"volume_score": 0}
        )

        self.assertTrue(result["audio_analysis_available"])
        self.assertEqual(result["speech_speed_score"], 100)
        self.assertEqual(result["silence_score"], 100)
        self.assertEqual(result["filler_score"], 100)


if __name__ == "__main__":
    unittest.main()
