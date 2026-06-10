import whisper

model = whisper.load_model("base")


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

    word_count = len(text.split())

    speech_speed = 0
    if duration > 0:
        speech_speed = round((word_count / duration) * 60, 2)

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
        "word_count": word_count,
        "speech_speed_wpm": speech_speed,
        "silence_count": len(silence_segments),
        "total_silence_time": total_silence_time,
        "silence_segments": silence_segments,
        "segments": segments
    }