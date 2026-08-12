import queue
import threading
from pathlib import Path
from typing import Any, Dict, List

from app.core import model_registry
from app.core.settings import get_settings

WHISPER_MODEL_SIZE = model_registry.WHISPER_MODEL_SIZE


def resolve_transcribe_timeout_seconds() -> float:
    return get_settings().whisper_transcribe_timeout_seconds


def _run_whisper_transcription(
    audio_path: str,
    result_queue: "queue.Queue[tuple[str, Any]]",
) -> None:
    # Python cannot forcibly stop a thread blocked inside faster-whisper/ctranslate2.
    # Keep it daemonized so a damaged audio file cannot block process shutdown. The
    # request returns on timeout while the model slot is recovered if the call finishes.
    try:
        with model_registry.whisper_model_context() as model:
            segments_generator, info = model.transcribe(
                audio_path,
                language="ko",
                beam_size=5,
                vad_filter=True,
            )

            segments: List[Dict[str, Any]] = []
            full_text_parts: List[str] = []

            for segment in segments_generator:
                text = segment.text.strip()

                if text:
                    full_text_parts.append(text)

                segments.append(
                    {
                        "start": round(segment.start, 2),
                        "end": round(segment.end, 2),
                        "duration": round(segment.end - segment.start, 2),
                        "text": text,
                    }
                )

            payload = {
                "language": info.language,
                "languageProbability": round(info.language_probability, 4),
                "transcript": " ".join(full_text_parts).strip(),
                "segments": segments,
            }

        result_queue.put(("success", payload))
    except Exception as exception:
        result_queue.put(("error", exception))


def transcribe_audio(audio_extraction_result: Dict[str, Any]) -> Dict[str, Any]:
    if not audio_extraction_result.get("success"):
        return create_empty_stt_result(
            reason="오디오 추출에 실패하여 STT를 수행하지 못했습니다.",
        )

    audio_path = audio_extraction_result.get("audioPath", "")

    if not audio_path or not Path(audio_path).exists():
        return create_empty_stt_result(
            reason="STT 대상 오디오 파일을 찾을 수 없습니다.",
        )

    try:
        timeout_seconds = resolve_transcribe_timeout_seconds()
        result_queue: "queue.Queue[tuple[str, Any]]" = queue.Queue(maxsize=1)
        worker = threading.Thread(
            target=_run_whisper_transcription,
            args=(audio_path, result_queue),
            daemon=True,
            name="whisper-transcribe-worker",
        )
        worker.start()

        try:
            status, payload = result_queue.get(timeout=timeout_seconds)
        except queue.Empty as exception:
            raise TimeoutError(
                f"Whisper STT가 {timeout_seconds}초 내에 끝나지 않아 중단합니다."
            ) from exception

        if status == "error":
            raise payload

        transcript = payload["transcript"]
        segments = payload["segments"]
        return {
            "success": True,
            "analysisMethod": "faster_whisper",
            "modelSize": WHISPER_MODEL_SIZE,
            "language": payload["language"],
            "languageProbability": payload["languageProbability"],
            "transcript": transcript,
            "segments": segments,
            "segmentCount": len(segments),
            "wordCount": count_words_for_presentation(transcript),
            "error": "",
        }
    except Exception as exception:
        return create_empty_stt_result(reason=str(exception))


def create_empty_stt_result(reason: str) -> Dict[str, Any]:
    return {
        "success": False,
        "analysisMethod": "faster_whisper",
        "modelSize": WHISPER_MODEL_SIZE,
        "language": "unknown",
        "languageProbability": 0,
        "transcript": "",
        "segments": [],
        "segmentCount": 0,
        "wordCount": 0,
        "error": reason,
    }


def count_words_for_presentation(text: str) -> int:
    normalized_text = text.strip()

    if not normalized_text:
        return 0

    return len(normalized_text.split())
