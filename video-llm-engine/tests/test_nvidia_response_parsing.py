"""NVIDIA(신뢰할 수 없는 모델) 응답을 파싱·검증하는 방어 분기의 특성화 테스트.

parse_model_json / extract_chat_completion_content / require_number /
require_string / 관찰 구간 검증은 모델이 이상한 JSON을 돌려줘도 크래시하거나
잘못된 데이터를 그대로 통과시키지 않게 막는 경로입니다. 기존 테스트는 정상 응답과
일부 누락 케이스만 다뤄, 아래 방어 분기 자체는 회귀 방어가 없었습니다.
현재 동작을 그대로 고정만 하며 바꾸지 않습니다(기대값은 실제 함수 실행으로 확인).
"""

import pytest

from app.services import nvidia_response as v


class TestParseModelJson:
    def test_strips_markdown_code_fence(self):
        assert v.parse_model_json('```json\n{"a": 1}\n```') == {"a": 1}

    def test_parses_plain_json_object(self):
        assert v.parse_model_json('{"b": 2}') == {"b": 2}

    def test_rejects_invalid_json(self):
        with pytest.raises(ValueError, match="not valid JSON"):
            v.parse_model_json("not json")

    def test_rejects_non_object_json(self):
        with pytest.raises(ValueError, match="must be an object"):
            v.parse_model_json("[1, 2, 3]")


class TestExtractChatCompletionContent:
    def test_returns_string_content(self):
        response = {"choices": [{"message": {"content": "hello"}}]}
        assert v.extract_chat_completion_content(response) == "hello"

    def test_joins_list_of_text_parts(self):
        response = {
            "choices": [
                {
                    "message": {
                        "content": [
                            {"type": "text", "text": "a"},
                            {"type": "text", "text": "b"},
                        ]
                    }
                }
            ]
        }
        assert v.extract_chat_completion_content(response) == "ab"

    def test_raises_when_content_missing(self):
        with pytest.raises(ValueError, match="missing choices"):
            v.extract_chat_completion_content({"choices": []})

    def test_rejects_non_string_text_part_as_invalid_provider_content(self):
        response = {
            "choices": [
                {
                    "message": {
                        "content": [
                            {"type": "text", "text": 42},
                        ]
                    }
                }
            ]
        }

        with pytest.raises(ValueError, match="content must be a JSON string"):
            v.extract_chat_completion_content(response)


class TestRequireNumberAndString:
    def test_require_number_accepts_int_and_float(self):
        assert v.require_number({"x": 3.5}, "x", "cat", 0) == 3.5
        assert v.require_number({"x": 7}, "x", "cat", 0) == 7

    def test_require_number_rejects_bool(self):
        # bool은 int의 하위 타입이지만 숫자로 취급하면 안 됩니다(True==1로 새면 안 됨).
        with pytest.raises(ValueError, match="must be a finite number"):
            v.require_number({"x": True}, "x", "cat", 0)

    def test_require_string_trims_and_returns(self):
        assert v.require_string({"s": "  hi "}, "s", "cat", 0) == "hi"

    def test_require_string_rejects_blank(self):
        with pytest.raises(ValueError, match="non-empty string"):
            v.require_string({"s": "   "}, "s", "cat", 0)


class TestObservationRangeValidation:
    def _obs(self, start, end):
        return {
            "eyeContact": [
                {
                    "startSec": start,
                    "endSec": end,
                    "label": "x",
                    "description": "d",
                    "confidence": 0.5,
                }
            ]
        }

    def test_rejects_end_before_start(self):
        with pytest.raises(ValueError, match="endSec < startSec"):
            v.normalize_observation_list(self._obs(10, 5), "eyeContact", None)

    def test_accepts_valid_range(self):
        result = v.normalize_observation_list(self._obs(5, 10), "eyeContact", None)
        assert result[0]["startSec"] == 5
        assert result[0]["endSec"] == 10
        assert result[0]["label"] == "x"

    @pytest.mark.parametrize("bad_confidence", [-0.1, 1.1])
    def test_rejects_confidence_out_of_range(self, bad_confidence):
        obs = {
            "eyeContact": [
                {
                    "startSec": 0,
                    "endSec": 1,
                    "label": "x",
                    "description": "d",
                    "confidence": bad_confidence,
                }
            ]
        }
        with pytest.raises(ValueError, match="confidence must be between 0 and 1"):
            v.normalize_observation_list(obs, "eyeContact", None)


class TestNormalizeVideoLlmResponse:
    def test_builds_exact_public_response_and_trims_model_strings(self):
        item = {
            "startSec": 1,
            "endSec": 2.5,
            "label": "  steady  ",
            "description": "  안정적인 전달  ",
            "confidence": 0.8,
            "ignored": "provider-only-field",
        }
        model_json = {
            "observations": {
                "eyeContact": [item],
                "facialExpression": [],
                "gesture": [],
                "posture": [],
                "ignoredCategory": [item],
            },
            "globalSummary": {
                "visualDelivery": "  전반 요약  ",
                "mainStrength": "  강점  ",
                "mainWeakness": "  약점  ",
                "ignoredSummary": "provider-only-field",
            },
            "ignoredTopLevel": True,
        }

        response = v.normalize_video_llm_response(
            "response-contract-job",
            "test-model",
            model_json,
            duration_sec=10.0,
        )

        assert response == {
            "jobId": "response-contract-job",
            "status": "success",
            "model": {
                "name": "test-model",
                "version": "nvidia-nim",
                "generationMode": "REAL",
            },
            "observations": {
                "eyeContact": [
                    {
                        "startSec": 1,
                        "endSec": 2.5,
                        "label": "steady",
                        "description": "안정적인 전달",
                        "confidence": 0.8,
                    }
                ],
                "facialExpression": [],
                "gesture": [],
                "posture": [],
            },
            "globalSummary": {
                "visualDelivery": "전반 요약",
                "mainStrength": "강점",
                "mainWeakness": "약점",
            },
        }
