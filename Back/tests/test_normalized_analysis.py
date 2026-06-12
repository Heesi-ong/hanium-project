import unittest

from Back.app.services.audio_analyzer import calculate_speech_rates
from Back.app.services.filler_analyzer import analyze_filler_words
from Back.app.services.gesture_analyzer import analyze_gesture_from_pose_results


class NormalizedAnalysisTests(unittest.TestCase):
    def test_korean_syllable_rate_does_not_depend_on_spaces(self):
        result = calculate_speech_rates("안녕하세요발표를시작합니다", 6)

        self.assertEqual(result["word_count"], 1)
        self.assertGreater(result["speech_speed_spm"], result["speech_speed_wpm"])
        self.assertEqual(result["speech_speed_basis"], "korean_syllables_per_minute")

    def test_filler_score_is_normalized_by_duration(self):
        text = "음 음 음 음"

        short = analyze_filler_words(text, 30)
        long = analyze_filler_words(text, 300)

        self.assertGreater(short["filler_per_minute"], long["filler_per_minute"])
        self.assertLess(short["filler_score"], long["filler_score"])

    def test_gesture_score_is_normalized_by_duration(self):
        poses = []
        for index in range(5):
            value = 0.1 if index % 2 == 0 else 0.3
            poses.append(
                {
                    "pose_detected": True,
                    "landmarks": [
                        {"id": 15, "x": value, "y": value},
                        {"id": 16, "x": value, "y": value},
                    ],
                }
            )

        short = analyze_gesture_from_pose_results(poses, 30)
        long = analyze_gesture_from_pose_results(poses, 300)

        self.assertGreater(short["gesture_per_minute"], long["gesture_per_minute"])
        self.assertNotEqual(short["gesture_score"], long["gesture_score"])


if __name__ == "__main__":
    unittest.main()
