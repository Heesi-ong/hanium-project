"""STT 실패 시 사용되는 '영상 길이 기반 추정' 폴백 경로의 특성화 테스트.

이 경로는 오디오는 추출됐지만 STT(음성→텍스트)에 실패했을 때 실제로 실행되는
운영 경로입니다. 기존 스코어링 테스트는 STT 성공 경로 위주라 이 폴백 계산은
회귀 방어가 없었습니다. 여기서는 현재 동작을 그대로 고정만 하며 값을 바꾸지 않습니다.
(기대값은 각 함수 본문을 그대로 실행해 확인했습니다.)
"""

import pytest

from app.api import basic_analysis as basic


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("", 0),
        ("   ", 0),
        ("a b c", 3),
        ("  안녕 하세요  ", 2),
    ],
)
def test_count_words_for_presentation(text, expected):
    assert basic.count_words_for_presentation(text) == expected


def test_calculate_segment_speech_duration_ignores_negative_and_rounds():
    assert basic.calculate_segment_speech_duration([]) == 0.0
    assert (
        basic.calculate_segment_speech_duration(
            [{"duration": 2.0}, {"duration": 3.55}, {"duration": -1}]
        )
        == 5.55
    )


@pytest.mark.parametrize(
    ("speech_duration_sec", "expected"),
    [
        (0, 0),
        (-5, 0),
        (60, 130),
        (49.2, 106),
    ],
)
def test_estimate_word_count_uses_130_wpm_baseline(speech_duration_sec, expected):
    assert basic.estimate_word_count(speech_duration_sec) == expected


@pytest.mark.parametrize(
    ("duration_sec", "expected"),
    [
        (0, 0),
        (10, 1),   # 20초 미만이어도 최소 1
        (20, 1),
        (45, 2),
        (60, 3),
    ],
)
def test_estimate_silence_count_one_per_20s_with_floor(duration_sec, expected):
    assert basic.estimate_silence_count(duration_sec) == expected


@pytest.mark.parametrize(
    ("word_count", "duration_sec", "expected"),
    [
        (0, 60, 0),
        (130, 0, 0),
        (130, 60, 3),
        (40, 60, 1),
    ],
)
def test_estimate_filler_count_is_2_5_percent_of_words(word_count, duration_sec, expected):
    assert basic.estimate_filler_count(word_count, duration_sec) == expected


def test_analyze_speech_from_video_duration_60s_snapshot():
    audio_extraction_result = {"status": "ok"}
    stt_result = {"success": False, "reason": "stt_failed"}

    result = basic.analyze_speech_from_video_duration(
        duration_sec=60,
        audio_extraction_result=audio_extraction_result,
        stt_result=stt_result,
    )

    # 60초 영상: 말하는 시간 82%(49.2s), 나머지 10.8s는 침묵으로 추정.
    assert result["analysisMethod"] == "audio_extracted_duration_based_estimation"
    assert result["durationSec"] == 60
    assert result["estimatedSpeechDurationSec"] == 49.2
    assert result["estimatedPauseDurationSec"] == 10.8
    assert result["estimatedWordCount"] == 106
    assert result["speechSpeedWpm"] == 129
    assert result["speechSpeedScore"] == 100
    assert result["silenceCount"] == 3
    assert result["silenceRatio"] == 0.18
    assert result["silenceScore"] == 80
    assert result["totalSilenceTime"] == 10.8
    # speechScore는 이 단계에서 0이고 finalize_speech_score()에서 확정됩니다.
    assert result["speechScore"] == 0
    assert result["stt"] is stt_result
    assert result["audioExtraction"] is audio_extraction_result


def test_analyze_speech_from_video_duration_clamps_negative_duration():
    result = basic.analyze_speech_from_video_duration(
        duration_sec=-10,
        audio_extraction_result={},
        stt_result={},
    )

    assert result["durationSec"] == 0
    assert result["estimatedSpeechDurationSec"] == 0.0
    assert result["estimatedWordCount"] == 0
    assert result["silenceCount"] == 0
