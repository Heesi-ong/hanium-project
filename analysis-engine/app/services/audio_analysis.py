import logging
import math
import statistics
import wave
from array import array
from pathlib import Path
from typing import Any, Dict, List

from app.services import speech_to_text

logger = logging.getLogger("analysis-engine")

# 음성 점수 내부 가중치입니다. '발표_코칭_점수화_알고리즘_선정_자료'의 권장 기준을 따릅니다.
# 말속도 35% + 침묵 25% + 필러 25% + 음량 안정성 15%.
SPEECH_SPEED_WEIGHT = 0.35
SILENCE_WEIGHT = 0.25
FILLER_WEIGHT = 0.25
VOLUME_STABILITY_WEIGHT = 0.15

# 음량 안정성은 추출된 WAV를 0.5초 단위로 나누고, 비침묵 구간의 RMS dBFS 표준편차로 계산합니다.
# STT 실패 시에도 오디오 추출만 성공하면 같은 계산값을 사용하고, 오디오가 없거나 너무 짧으면
# 기존 중립 기본값(80점)으로 fallback합니다.
VOLUME_STABILITY_BASELINE_SCORE = 80
VOLUME_STABILITY_IMPLEMENTED = True
VOLUME_ANALYSIS_WINDOW_SEC = 0.5
VOLUME_SILENCE_DBFS_THRESHOLD = -55.0

KOREAN_FILLER_WORDS = [
    "음",
    "어",
    "아",
    "그",
    "저",
    "음...",
    "어...",
    "아...",
    "그...",
    "저...",
    "그러니까",
    "뭐",
    "약간",
    "이제",
    "사실",
    "일단",
    "막",
    "좀",
]


def analyze_speech(
    duration_sec: float,
    audio_extraction_result: Dict[str, Any],
    stt_result: Dict[str, Any],
) -> Dict[str, Any]:
    if stt_result.get("success"):
        return analyze_speech_from_stt(
            duration_sec=duration_sec,
            audio_extraction_result=audio_extraction_result,
            stt_result=stt_result,
        )

    return analyze_speech_from_video_duration(
        duration_sec=duration_sec,
        audio_extraction_result=audio_extraction_result,
        stt_result=stt_result,
    )


def analyze_speech_from_stt(
    duration_sec: float,
    audio_extraction_result: Dict[str, Any],
    stt_result: Dict[str, Any],
) -> Dict[str, Any]:
    safe_duration_sec = max(duration_sec, 0)

    segments = stt_result.get("segments", [])
    word_count = int(stt_result.get("wordCount", 0))

    speech_duration_sec = calculate_segment_speech_duration(segments)
    pause_analysis = analyze_pauses_from_segments(
        segments=segments,
        total_duration_sec=safe_duration_sec,
    )

    speech_speed_wpm = calculate_speech_speed_wpm(
        word_count=word_count,
        speech_duration_sec=speech_duration_sec,
    )

    speech_speed_score = calculate_speech_speed_score(speech_speed_wpm)
    silence_score = calculate_silence_score(pause_analysis["silenceRatio"])
    volume_analysis = analyze_volume_stability(audio_extraction_result)

    return {
        "analysisMethod": "stt_based_analysis",
        "audioExtraction": audio_extraction_result,
        "stt": stt_result,
        "durationSec": safe_duration_sec,
        "estimatedSpeechDurationSec": speech_duration_sec,
        "estimatedPauseDurationSec": pause_analysis["totalSilenceTime"],
        "estimatedWordCount": word_count,
        "speechSpeedWpm": speech_speed_wpm,
        "speechSpeedScore": speech_speed_score,
        "silenceCount": pause_analysis["silenceCount"],
        "totalSilenceTime": pause_analysis["totalSilenceTime"],
        "silenceRatio": pause_analysis["silenceRatio"],
        "silenceScore": silence_score,
        **volume_analysis,
        # speechScore는 finalize_speech_score()에서 말속도/침묵/필러/음량을 합쳐 확정합니다.
        "speechScore": 0,
        "note": "faster-whisper STT 결과를 기반으로 말하기 속도와 침묵 구간을 계산했습니다.",
    }


def analyze_speech_from_video_duration(
    duration_sec: float,
    audio_extraction_result: Dict[str, Any],
    stt_result: Dict[str, Any],
) -> Dict[str, Any]:
    safe_duration_sec = max(duration_sec, 0)
    estimated_speech_duration_sec = round(safe_duration_sec * 0.82, 2)
    estimated_pause_duration_sec = round(
        safe_duration_sec - estimated_speech_duration_sec,
        2,
    )

    estimated_word_count = estimate_word_count(estimated_speech_duration_sec)

    speech_speed_wpm = calculate_speech_speed_wpm(
        word_count=estimated_word_count,
        speech_duration_sec=estimated_speech_duration_sec,
    )

    silence_count = estimate_silence_count(safe_duration_sec)

    silence_ratio = calculate_silence_ratio(
        silence_duration_sec=estimated_pause_duration_sec,
        duration_sec=safe_duration_sec,
    )

    speech_speed_score = calculate_speech_speed_score(speech_speed_wpm)
    silence_score = calculate_silence_score(silence_ratio)
    volume_analysis = analyze_volume_stability(audio_extraction_result)
    fallback_note = (
        "오디오는 추출했지만 STT에 실패하여 영상 길이 기반 추정값을 사용했습니다."
        if audio_extraction_result.get("success")
        else "오디오 추출 또는 STT에 실패하여 영상 길이 기반 추정값을 사용했습니다."
    )

    return {
        "analysisMethod": "audio_extracted_duration_based_estimation",
        "audioExtraction": audio_extraction_result,
        "stt": stt_result,
        "durationSec": safe_duration_sec,
        "estimatedSpeechDurationSec": estimated_speech_duration_sec,
        "estimatedPauseDurationSec": estimated_pause_duration_sec,
        "estimatedWordCount": estimated_word_count,
        "speechSpeedWpm": speech_speed_wpm,
        "speechSpeedScore": speech_speed_score,
        "silenceCount": silence_count,
        "totalSilenceTime": estimated_pause_duration_sec,
        "silenceRatio": silence_ratio,
        "silenceScore": silence_score,
        **volume_analysis,
        # speechScore는 finalize_speech_score()에서 말속도/침묵/필러/음량을 합쳐 확정합니다.
        "speechScore": 0,
        "note": fallback_note,
    }


def analyze_volume_stability(audio_extraction_result: Dict[str, Any]) -> Dict[str, Any]:
    audio_path = audio_extraction_result.get("audioPath")

    if not audio_extraction_result.get("success") or not audio_path:
        return create_volume_stability_fallback("audio_unavailable")

    try:
        return calculate_volume_stability_from_wav(Path(audio_path))
    except Exception:
        # exc_info=True가 현재 예외 정보를 로그에 남기므로 별도 바인딩이 필요 없습니다.
        logger.warning(
            "음량 안정성 분석에 실패해 중립 기본값을 사용합니다. audioPath=%s",
            audio_path,
            exc_info=True,
        )
        return create_volume_stability_fallback("analysis_failed")


def create_volume_stability_fallback(reason: str) -> Dict[str, Any]:
    return {
        "volumeStabilityScore": VOLUME_STABILITY_BASELINE_SCORE,
        "volumeStabilityImplemented": False,
        "volumeStabilityFallbackReason": reason,
        "volumeRmsDbStdDev": None,
        "volumeAnalyzedWindowCount": 0,
        "volumeSilentWindowCount": 0,
    }


def calculate_volume_stability_from_wav(audio_path: Path) -> Dict[str, Any]:
    if not audio_path.exists() or audio_path.stat().st_size == 0:
        return create_volume_stability_fallback("audio_file_missing")

    non_silent_dbfs_values: List[float] = []
    silent_window_count = 0
    total_window_count = 0

    with wave.open(str(audio_path), "rb") as wav_file:
        sample_width = wav_file.getsampwidth()
        channel_count = wav_file.getnchannels()
        frame_rate = wav_file.getframerate()
        window_frame_count = max(int(frame_rate * VOLUME_ANALYSIS_WINDOW_SEC), 1)

        if sample_width != 2:
            return create_volume_stability_fallback("unsupported_sample_width")

        while True:
            frames = wav_file.readframes(window_frame_count)
            if not frames:
                break

            total_window_count += 1
            rms = calculate_pcm16_rms(frames, channel_count)
            dbfs = calculate_dbfs(rms)

            if dbfs <= VOLUME_SILENCE_DBFS_THRESHOLD:
                silent_window_count += 1
                continue

            non_silent_dbfs_values.append(dbfs)

    if len(non_silent_dbfs_values) < 2:
        fallback = create_volume_stability_fallback("insufficient_non_silent_audio")
        fallback["volumeSilentWindowCount"] = silent_window_count
        fallback["volumeAnalyzedWindowCount"] = total_window_count
        return fallback

    std_dev = calculate_population_std_dev(non_silent_dbfs_values)
    score = calculate_volume_stability_score(std_dev)

    return {
        "volumeStabilityScore": score,
        "volumeStabilityImplemented": True,
        "volumeStabilityFallbackReason": "",
        "volumeRmsDbStdDev": round(std_dev, 2),
        "volumeAnalyzedWindowCount": total_window_count,
        "volumeSilentWindowCount": silent_window_count,
    }


def calculate_pcm16_rms(frames: bytes, channel_count: int) -> float:
    if not frames:
        return 0.0

    samples = array("h")
    samples.frombytes(frames)

    if channel_count > 1:
        samples = array("h", samples[::channel_count])

    if not samples:
        return 0.0

    square_sum = sum(sample * sample for sample in samples)
    return math.sqrt(square_sum / len(samples))


def calculate_dbfs(rms: float) -> float:
    if rms <= 0:
        return -120.0

    return 20 * math.log10(rms / 32768.0)


def calculate_population_std_dev(values: List[float]) -> float:
    return statistics.pstdev(values)


def calculate_volume_stability_score(dbfs_std_dev: float) -> int:
    if dbfs_std_dev <= 3:
        return 100

    if dbfs_std_dev <= 6:
        return 80

    if dbfs_std_dev <= 10:
        return 60

    return 40


def blend_speech_score(
    speech_speed_score: int,
    silence_score: int,
    filler_score: int,
    volume_stability_score: int,
) -> int:
    """말속도/침묵/필러/음량 안정성 점수를 문서 권장 가중치로 합칩니다.

    가중치: 말속도 35% + 침묵 25% + 필러 25% + 음량 안정성 15%.
    """
    blended = int(
        speech_speed_score * SPEECH_SPEED_WEIGHT
        + silence_score * SILENCE_WEIGHT
        + filler_score * FILLER_WEIGHT
        + volume_stability_score * VOLUME_STABILITY_WEIGHT
    )

    return max(0, min(blended, 100))


def finalize_speech_score(
    audio_result: Dict[str, Any],
    filler_result: Dict[str, Any],
) -> Dict[str, Any]:
    """필러 분석이 끝난 뒤 음성 점수를 최종 확정합니다.

    필러 점수는 필러 분석 이후에야 알 수 있어, 자세/시선처럼 즉시 계산하지 않고
    이 함수에서 마지막으로 합칩니다. 음량 안정성은 오디오 추출이 성공하면 WAV RMS 변동성으로
    이미 계산되어 있고, 오디오가 부족하면 중립 기본값으로 fallback됩니다.
    """
    speech_speed_score = int(audio_result.get("speechSpeedScore", 0))
    silence_score = int(audio_result.get("silenceScore", 0))
    filler_score = int(filler_result.get("fillerScore", 0))
    volume_stability_score = int(
        audio_result.get("volumeStabilityScore", VOLUME_STABILITY_BASELINE_SCORE)
    )

    speech_score = blend_speech_score(
        speech_speed_score=speech_speed_score,
        silence_score=silence_score,
        filler_score=filler_score,
        volume_stability_score=volume_stability_score,
    )

    audio_result["fillerScore"] = filler_score
    audio_result["volumeStabilityScore"] = volume_stability_score
    audio_result["volumeStabilityImplemented"] = bool(
        audio_result.get("volumeStabilityImplemented", VOLUME_STABILITY_IMPLEMENTED)
    )
    audio_result["speechScore"] = speech_score
    audio_result["speechScoreWeights"] = {
        "speechSpeed": SPEECH_SPEED_WEIGHT,
        "silence": SILENCE_WEIGHT,
        "filler": FILLER_WEIGHT,
        "volumeStability": VOLUME_STABILITY_WEIGHT,
    }

    return audio_result


def calculate_segment_speech_duration(segments: List[Dict[str, Any]]) -> float:
    total_duration = 0.0

    for segment in segments:
        duration = float(segment.get("duration", 0))
        total_duration += max(duration, 0)

    return round(total_duration, 2)


def analyze_pauses_from_segments(
    segments: List[Dict[str, Any]],
    total_duration_sec: float,
) -> Dict[str, Any]:
    if not segments or total_duration_sec <= 0:
        return {
            "silenceCount": 0,
            "totalSilenceTime": 0,
            "silenceRatio": 0,
        }

    sorted_segments = sorted(
        segments,
        key=lambda segment: float(segment.get("start", 0)),
    )

    silence_threshold_sec = 1.0
    silence_count = 0
    total_silence_time = 0.0

    previous_end = 0.0

    for segment in sorted_segments:
        start = float(segment.get("start", 0))
        end = float(segment.get("end", 0))

        gap = max(start - previous_end, 0)

        if gap >= silence_threshold_sec:
            silence_count += 1
            total_silence_time += gap

        previous_end = max(previous_end, end)

    tail_gap = max(total_duration_sec - previous_end, 0)

    if tail_gap >= silence_threshold_sec:
        silence_count += 1
        total_silence_time += tail_gap

    total_silence_time = round(total_silence_time, 2)
    silence_ratio = calculate_silence_ratio(
        silence_duration_sec=total_silence_time,
        duration_sec=total_duration_sec,
    )

    return {
        "silenceCount": silence_count,
        "totalSilenceTime": total_silence_time,
        "silenceRatio": silence_ratio,
    }


def estimate_word_count(speech_duration_sec: float) -> int:
    if speech_duration_sec <= 0:
        return 0

    baseline_wpm = 130
    return int((speech_duration_sec / 60) * baseline_wpm)


def calculate_speech_speed_wpm(
    word_count: int,
    speech_duration_sec: float,
) -> int:
    if speech_duration_sec <= 0:
        return 0

    return int(word_count / (speech_duration_sec / 60))


def estimate_silence_count(duration_sec: float) -> int:
    if duration_sec <= 0:
        return 0

    return max(1, int(duration_sec // 20))


def calculate_silence_ratio(
    silence_duration_sec: float,
    duration_sec: float,
) -> float:
    if duration_sec <= 0:
        return 0

    return round(silence_duration_sec / duration_sec, 4)


def calculate_speech_speed_score(speech_speed_wpm: int) -> int:
    if 110 <= speech_speed_wpm <= 150:
        return 100

    if 90 <= speech_speed_wpm < 110:
        return 80

    if 150 < speech_speed_wpm <= 170:
        return 80

    if 70 <= speech_speed_wpm < 90:
        return 60

    if 170 < speech_speed_wpm <= 190:
        return 60

    return 40


def calculate_silence_score(silence_ratio: float) -> int:
    if silence_ratio <= 0.15:
        return 100

    if silence_ratio <= 0.25:
        return 80

    if silence_ratio <= 0.35:
        return 60

    return 40


def analyze_filler_from_transcript(
    stt_result: Dict[str, Any],
    audio_result: Dict[str, Any],
) -> Dict[str, Any]:
    transcript = stt_result.get("transcript", "")
    estimated_word_count = int(audio_result.get("estimatedWordCount", 0))

    if stt_result.get("success") and transcript:
        filler_detail = count_filler_words(transcript)
        filler_count = filler_detail["totalCount"]
        filler_ratio = calculate_filler_ratio(
            filler_count=filler_count,
            estimated_word_count=max(estimated_word_count, 1),
        )

        filler_score = calculate_filler_score(filler_ratio)

        return {
            "analysisMethod": "stt_based_filler_detection",
            "fillerWords": filler_detail["items"],
            "fillerCount": filler_count,
            "fillerRatio": filler_ratio,
            "fillerScore": filler_score,
            "note": "STT 변환 텍스트에서 한국어 필러 표현을 탐지했습니다.",
        }

    filler_count = estimate_filler_count(
        estimated_word_count=estimated_word_count,
        duration_sec=float(audio_result.get("durationSec", 0)),
    )

    filler_ratio = calculate_filler_ratio(
        filler_count=filler_count,
        estimated_word_count=estimated_word_count,
    )

    filler_score = calculate_filler_score(filler_ratio)

    return {
        "analysisMethod": "duration_based_estimation",
        "fillerWords": [],
        "fillerCount": filler_count,
        "fillerRatio": filler_ratio,
        "fillerScore": filler_score,
        "note": "STT 결과가 없어 필러 수를 추정값으로 계산했습니다.",
    }


def count_filler_words(transcript: str) -> Dict[str, Any]:
    items: List[Dict[str, Any]] = []
    total_count = 0

    normalized_transcript = transcript.replace(",", " ").replace(".", " ")
    tokens = normalized_transcript.split()

    for filler_word in KOREAN_FILLER_WORDS:
        count = tokens.count(filler_word)

        if count <= 0:
            continue

        items.append(
            {
                "word": filler_word,
                "count": count,
            }
        )

        total_count += count

    return {
        "totalCount": total_count,
        "items": items,
    }


def estimate_filler_count(
    estimated_word_count: int,
    duration_sec: float,
) -> int:
    if estimated_word_count <= 0 or duration_sec <= 0:
        return 0

    return max(0, int(estimated_word_count * 0.025))


def calculate_filler_ratio(
    filler_count: int,
    estimated_word_count: int,
) -> float:
    if estimated_word_count <= 0:
        return 0

    return round(filler_count / estimated_word_count, 4)


def calculate_filler_score(filler_ratio: float) -> int:
    if filler_ratio <= 0.01:
        return 100

    if filler_ratio <= 0.03:
        return 80

    if filler_ratio <= 0.06:
        return 60

    return 40


def create_empty_audio_result() -> Dict[str, Any]:
    empty_stt = speech_to_text.create_empty_stt_result(
        reason="영상 정보를 읽지 못해 STT를 수행하지 못했습니다.",
    )

    return {
        "analysisMethod": "stt_based_analysis",
        "audioExtraction": {
            "success": False,
            "audioPath": "",
            "fileSize": 0,
            "sampleRate": 16000,
            "channelCount": 1,
            "codec": "pcm_s16le",
            "error": "영상 정보를 읽지 못해 오디오를 추출하지 못했습니다.",
        },
        "stt": empty_stt,
        "durationSec": 0,
        "estimatedSpeechDurationSec": 0,
        "estimatedPauseDurationSec": 0,
        "estimatedWordCount": 0,
        "speechSpeedWpm": 0,
        "speechSpeedScore": 0,
        "silenceCount": 0,
        "totalSilenceTime": 0,
        "silenceRatio": 0,
        "silenceScore": 0,
        "volumeStabilityScore": 0,
        "volumeStabilityImplemented": False,
        "volumeStabilityFallbackReason": "audio_unavailable",
        "volumeRmsDbStdDev": None,
        "volumeAnalyzedWindowCount": 0,
        "volumeSilentWindowCount": 0,
        "fillerScore": 0,
        "speechScore": 0,
        "note": "영상 정보를 읽지 못해 음성 분석을 수행하지 못했습니다.",
    }


def create_empty_filler_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "stt_based_filler_detection",
        "fillerWords": [],
        "fillerCount": 0,
        "fillerRatio": 0,
        "fillerScore": 0,
        "note": "영상 정보를 읽지 못해 필러 분석을 수행하지 못했습니다.",
    }
