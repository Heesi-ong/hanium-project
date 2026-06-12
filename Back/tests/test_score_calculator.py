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
        self.assertIsNone(result["total_score"])
        self.assertIsNone(result["pose_detection_rate"])
        self.assertIsNone(result["face_detection_rate"])
        self.assertIsNone(result["shoulder_balance_score"])
        self.assertIsNone(result["gaze_score"])
        self.assertIsNone(result["gesture_score"])
        self.assertIsNone(result["volume_score"])
        self.assertEqual(result["available_score_count"], 0)

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
        self.assertEqual(result["total_score"], 100)

    def test_missing_visual_metrics_do_not_lower_available_audio_score(self):
        result = calculate_basic_score(
            video_info={"duration_seconds": 10},
            frame_result={"saved_count": 10},
            pose_results=[{"pose_detected": False}] * 10,
            face_results=[{"face_detected": False}] * 10,
            audio_result={
                "text": "발표 내용",
                "segments": [{"start": 0, "end": 10}],
                "speech_speed_wpm": 120,
                "silence_count": 0,
                "total_silence_time": 0,
                "filler_score": 100,
            },
            gesture_result={"gesture_score": 0},
            volume_result={"mean_volume_db": None, "volume_score": 0},
        )

        self.assertEqual(result["total_score"], 100)
        self.assertFalse(result["score_availability"]["gaze_score"])


if __name__ == "__main__":
    unittest.main()
