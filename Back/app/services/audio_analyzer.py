"""Whisper 음성 인식 결과를 만들고 발표 발화 텍스트와 말하기 속도 정보를 계산한다."""

import re

import whisper

model = whisper.load_model("base")


def calculate_speech_rates(text, duration):
    word_count = len(text.split())
    korean_syllable_count = len(re.findall(r"[가-힣]", text))
    if duration <= 0:
        return {
            "word_count": word_count,
            "korean_syllable_count": korean_syllable_count,
            "speech_speed_wpm": 0,
            "speech_speed_spm": 0,
            "speech_speed_basis": "unavailable",
        }
    return {
        "word_count": word_count,
        "korean_syllable_count": korean_syllable_count,
        "speech_speed_wpm": round((word_count / duration) * 60, 2),
        "speech_speed_spm": round((korean_syllable_count / duration) * 60, 2),
        "speech_speed_basis": "korean_syllables_per_minute" if korean_syllable_count else "words_per_minute",
    }


def analyze_audio_from_video(video_path: str):
    result = model.transcribe(
        video_path,
        language="ko"
    )

    text = result.get("text", "")
    segments = result.get("segments", [])

    duration = 0
    if segments:
        duration = segments[-1].get("end", 0)

    speech_rates = calculate_speech_rates(text, duration)

    silence_segments = []

    for i in range(len(segments) - 1):
        current_end = segments[i].get("end", 0)
        next_start = segments[i + 1].get("start", 0)

        gap = round(next_start - current_end, 2)

        if gap >= 2.0:
            silence_segments.append({
                "start": current_end,
                "end": next_start,
                "duration": gap
            })

    total_silence_time = round(
        sum(item["duration"] for item in silence_segments),
        2
    )

    return {
        "text": text,
        "duration_seconds": duration,
        **speech_rates,
        "silence_count": len(silence_segments),
        "total_silence_time": total_silence_time,
        "silence_segments": silence_segments,
        "segments": segments
    }
