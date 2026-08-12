from app.services import audio_analysis


def test_stt_speech_filler_and_final_score_contract():
    audio_extraction_result = {
        "success": False,
        "audioPath": "",
        "error": "characterization fixture",
    }
    stt_result = {
        "success": True,
        "transcript": "음 발표를 시작합니다",
        "wordCount": 10,
        "segments": [
            {"start": 1.0, "end": 3.0, "duration": 2.0},
            {"start": 5.0, "end": 8.0, "duration": 3.0},
        ],
    }

    audio_result = audio_analysis.analyze_speech(
        duration_sec=10,
        audio_extraction_result=audio_extraction_result,
        stt_result=stt_result,
    )
    filler_result = audio_analysis.analyze_filler_from_transcript(
        stt_result, audio_result
    )
    finalized = audio_analysis.finalize_speech_score(audio_result, filler_result)

    assert finalized["analysisMethod"] == "stt_based_analysis"
    assert finalized["durationSec"] == 10
    assert finalized["estimatedSpeechDurationSec"] == 5.0
    assert finalized["estimatedPauseDurationSec"] == 5.0
    assert finalized["estimatedWordCount"] == 10
    assert finalized["speechSpeedWpm"] == 120
    assert finalized["speechSpeedScore"] == 100
    assert finalized["silenceCount"] == 3
    assert finalized["totalSilenceTime"] == 5.0
    assert finalized["silenceRatio"] == 0.5
    assert finalized["silenceScore"] == 40
    assert finalized["volumeStabilityScore"] == 80
    assert finalized["volumeStabilityImplemented"] is False
    assert finalized["volumeStabilityFallbackReason"] == "audio_unavailable"
    assert finalized["fillerScore"] == 40
    assert finalized["speechScore"] == 67
    assert finalized["speechScoreWeights"] == {
        "speechSpeed": 0.35,
        "silence": 0.25,
        "filler": 0.25,
        "volumeStability": 0.15,
    }

    assert filler_result == {
        "analysisMethod": "stt_based_filler_detection",
        "fillerWords": [{"word": "음", "count": 1}],
        "fillerCount": 1,
        "fillerRatio": 0.1,
        "fillerScore": 40,
        "note": "STT 변환 텍스트에서 한국어 필러 표현을 탐지했습니다.",
    }
