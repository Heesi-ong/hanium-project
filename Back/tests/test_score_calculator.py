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

    def test_detection_rates_are_confidence_not_performance_scores(self):
        result = calculate_basic_score(
            video_info={"duration_seconds": 10},
            frame_result={"saved_count": 10},
            pose_results=[{"pose_detected": False}] * 7
            + [{"pose_detected": True, "landmarks": [{"id": 11, "y": 0.4}, {"id": 12, "y": 0.4}]}] * 3,
            face_results=[{"face_detected": False}] * 10,
            audio_result={"text": "", "segments": []},
            gesture_result={"gesture_score": 100},
            volume_result={},
        )

        self.assertEqual(result["pose_detection_rate"], 30)
        self.assertEqual(result["shoulder_balance_score"], 100)
        self.assertEqual(result["total_score"], 100)
        self.assertNotIn("pose_detection_rate", result["score_availability"])
        self.assertTrue(result["confidence_availability"]["pose_detection_rate"])

    def test_too_few_visual_frames_are_unavailable(self):
        result = calculate_basic_score(
            video_info={"duration_seconds": 10},
            frame_result={"saved_count": 10},
            pose_results=[
                {"pose_detected": True, "landmarks": [{"id": 11, "y": 0.4}, {"id": 12, "y": 0.4}]}
            ] + [{"pose_detected": False}] * 9,
            face_results=[{"face_detected": False}] * 10,
            audio_result={"text": "", "segments": []},
            gesture_result={"gesture_score": 100},
            volume_result={},
        )

        self.assertEqual(result["pose_detection_rate"], 10)
        self.assertIsNone(result["shoulder_balance_score"])
        self.assertIsNone(result["gesture_score"])
        self.assertFalse(result["analysis_confidence"]["visual"]["pose_evaluation_available"])

    def test_head_direction_is_parallel_metric_and_does_not_change_total_score(self):
        face = {
            "face_detected": True,
            "landmarks": [
                {"id": 1, "x": 0.5},
                {"id": 33, "x": 0.4},
                {"id": 263, "x": 0.6},
            ],
            "head_direction_score": 40,
        }
        result = calculate_basic_score(
            video_info={"duration_seconds": 3},
            frame_result={"saved_count": 3},
            pose_results=[{"pose_detected": False}] * 3,
            face_results=[face] * 3,
            audio_result={"text": "", "segments": []},
            gesture_result={},
            volume_result={},
        )

        self.assertEqual(result["gaze_score"], 100)
        self.assertEqual(result["head_direction_score"], 40)
        self.assertEqual(result["total_score"], 100)
        self.assertNotIn("head_direction_score", result["score_availability"])


if __name__ == "__main__":
    unittest.main()
