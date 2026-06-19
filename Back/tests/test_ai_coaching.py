import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException

from Back.app.services import ai_coaching
from Back.app.services.practice_coaching import build_practice_coaching


def sample_result():
    return {
        "data": {
            "video_info": {"duration_seconds": 120},
            "summary_result": {"total_score": 78},
            "score_result": {
                "total_score": 78,
                "gaze_score": None,
                "speech_speed_score": 72,
                "volume_score": 80,
                "score_availability": {"gaze_score": False, "speech_speed_score": True},
                "analysis_confidence": {"visual": {"level": "limited"}, "audio": {"level": "high"}},
            },
            "audio_result": {
                "text": "오늘 프로젝트 결과를 발표합니다.",
                "speech_speed_spm": 310,
                "filler_count": 2,
                "filler_per_minute": 1,
                "segments": [{"start": 0, "end": 3, "text": "오늘 프로젝트 결과를 발표합니다."}],
            },
            "timeline_result": {"timeline": []},
        }
    }


class AiCoachingTests(unittest.TestCase):
    @patch("Back.app.services.ai_coaching.retrieve_knowledge", return_value=[])
    def test_unavailable_metric_is_not_used_as_rag_improvement_query(self, retrieve):
        result = sample_result()
        context = {"purpose": "project", "audience": "심사위원", "core_message": "검증 결과"}
        rule = build_practice_coaching(result, context)

        ai_coaching.build_structured_coaching_input(result, context, rule)

        metric_keys = retrieve.call_args.kwargs["metric_keys"]
        self.assertIn("speech_speed_score", metric_keys)
        self.assertNotIn("gaze_score", metric_keys)

    def test_structured_input_marks_unavailable_score_without_treating_it_as_low(self):
        result = sample_result()
        context = {"purpose": "project", "audience": "심사위원", "core_message": "검증 결과"}
        rule = build_practice_coaching(result, context)

        structured = ai_coaching.build_structured_coaching_input(result, context, rule)

        self.assertFalse(structured["system_scores"]["items"]["gaze_score"]["available"])
        self.assertNotIn("gaze_score: None점", structured["allowed_evidence"])
        self.assertIn("검증 결과", structured["presentation"]["core_message"])
        self.assertIn("본인 기여도", structured["presentation"]["coaching_direction"])

    def test_valid_json_response_uses_only_allowed_evidence(self):
        evidence = ["speech_speed_score: 72점"]
        content = json.dumps(
            {
                "summary": "핵심 메시지는 분명하지만 속도를 조정하세요.",
                "strengths": [{"title": "음량", "evidence": "speech_speed_score: 72점"}],
                "priorities": [
                    {
                        "title": "속도",
                        "reason": "이해 시간을 확보해야 합니다.",
                        "evidence": "speech_speed_score: 72점",
                        "action": "핵심 문장 뒤에 멈춥니다.",
                        "exercise": "1분 발표를 녹음합니다.",
                        "rewrite_example": "핵심 결과는 세 가지입니다.",
                    }
                ],
                "expected_questions": ["검증 방법은 무엇인가요?"],
                "limitations": ["시각 분석 신뢰도가 낮습니다."],
            },
            ensure_ascii=False,
        )

        coaching = ai_coaching.validate_coaching_response(content, evidence)

        self.assertEqual(coaching["priorities"][0]["evidence"], evidence[0])

    def test_unverified_evidence_is_rejected(self):
        content = json.dumps(
            {
                "summary": "요약",
                "strengths": [{"title": "장점", "evidence": "존재하지 않는 99점"}],
                "priorities": [],
                "expected_questions": [],
                "limitations": [],
            },
            ensure_ascii=False,
        )
        with self.assertRaises(ValueError):
            ai_coaching.validate_coaching_response(content, ["검증 가능한 분석 근거가 부족함"])

    def test_invalid_ollama_json_is_saved_as_rule_fallback(self):
        result = sample_result()
        context = {"purpose": "project", "audience": "심사위원", "core_message": "검증 결과"}
        rule = build_practice_coaching(result, context)
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            ai_coaching, "AI_COACHING_DIR", Path(temp_dir)
        ), patch.object(
            ai_coaching, "chat_with_ollama", return_value={
                "content": "잘못된 JSON",
                "input_tokens": 1,
                "output_tokens": 1,
                "total_tokens": 2,
            }
        ):
            saved = ai_coaching.generate_ai_coaching("job-1", 7, result, context, rule)
            loaded = ai_coaching.load_ai_coaching("job-1", 7)

        self.assertEqual(saved["status"], "fallback")
        self.assertEqual(loaded["result_id"], "job-1")
        self.assertIn("규칙 기반", saved["coaching"]["summary"])
        self.assertEqual(saved["failure_type"], "invalid_response")
        self.assertIn("knowledge_sources", saved)

    def test_ollama_failure_does_not_raise_and_preserves_fallback(self):
        result = sample_result()
        context = {"purpose": "class"}
        rule = build_practice_coaching(result, context)
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            ai_coaching, "AI_COACHING_DIR", Path(temp_dir)
        ), patch.object(
            ai_coaching,
            "chat_with_ollama",
            side_effect=HTTPException(status_code=503, detail="Ollama 서버에 연결할 수 없습니다."),
        ):
            saved = ai_coaching.generate_ai_coaching("job-1", 7, result, context, rule)

        self.assertEqual(saved["status"], "fallback")
        self.assertIn("Ollama 서버", saved["failure_reason"])
        self.assertEqual(saved["failure_type"], "ollama_unavailable")


if __name__ == "__main__":
    unittest.main()
