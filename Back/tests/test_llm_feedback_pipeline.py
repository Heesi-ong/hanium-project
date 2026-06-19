import unittest

from Back.app.services.analysis_filter import filter_analysis_result
from Back.app.services.llm_feedback_generator import generate_llm_feedback
from Back.app.services.llm_prompt_builder import build_llm_feedback_prompt


class LlmFeedbackPipelineTests(unittest.TestCase):
    def sample_analysis(self):
        return {
            "video_info": {"duration_seconds": 120, "fps": 30},
            "audio_result": {
                "speech_speed_wpm": 185,
                "speech_speed_spm": 430,
                "speech_speed_basis": "korean_syllables_per_minute",
                "silence_count": 7,
                "total_silence_time": 18,
                "filler_count": 12,
                "filler_words": {"음": 8, "어": 4},
            },
            "score_result": {
                "total_score": 58,
                "pose_detection_rate": 92,
                "face_detection_rate": 65,
                "shoulder_balance_score": 62,
                "gaze_score": 55,
                "gesture_score": 88,
                "volume_score": 90,
            },
            "feedback_result": {"summary": "기본 피드백"},
            "summary_result": {"total_score": 58, "summary_feedback": "기본 피드백"},
        }

    def test_filter_analysis_result_extracts_priority_issues(self):
        filtered = filter_analysis_result(self.sample_analysis())

        self.assertEqual(filtered["overall_level"], "개선 필요")
        categories = {item["category"] for item in filtered["priority_issues"]}
        self.assertIn("speech_speed", categories)
        self.assertIn("silence", categories)
        self.assertIn("filler", categories)
        self.assertIn("face_detection", categories)
        self.assertIn("gaze", categories)
        self.assertIn("posture", categories)
        self.assertIn("발화 속도 조절", filtered["improvement_targets"])
        self.assertTrue(filtered["strengths"])

    def test_prompt_builder_keeps_raw_and_filtered_inputs(self):
        raw = self.sample_analysis()
        filtered = filter_analysis_result(raw)

        prompt = build_llm_feedback_prompt(raw, filtered)

        self.assertEqual(prompt["prompt_version"], "presentation-feedback-filtered-2026.06.1")
        self.assertIn("JSON", prompt["system_instruction"])
        self.assertEqual(prompt["input_data"]["filtered_analysis_result"], filtered)
        self.assertEqual(
            prompt["input_data"]["raw_analysis_result"]["summary"]["total_score"],
            58,
        )

    def test_generate_llm_feedback_returns_expected_json_shape(self):
        raw = self.sample_analysis()
        filtered = filter_analysis_result(raw)

        feedback = generate_llm_feedback(raw, filtered)

        self.assertEqual(feedback["metadata"]["provider"], "mock")
        self.assertIsInstance(feedback["summary"], str)
        self.assertIsInstance(feedback["strengths"], list)
        self.assertIsInstance(feedback["weaknesses"], list)
        self.assertIn("speech", feedback["detailed_feedback"])
        self.assertEqual(len(feedback["practice_plan"]), 3)
        self.assertTrue(feedback["final_advice"])


if __name__ == "__main__":
    unittest.main()
