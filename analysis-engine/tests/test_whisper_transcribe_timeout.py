import time
from contextlib import contextmanager
from typing import Any, Dict

import pytest

from app.core import model_registry
from app.services import speech_to_text


class FakeSegment:
    def __init__(self, start: float, end: float, text: str):
        self.start = start
        self.end = end
        self.text = text


class FakeInfo:
    def __init__(self, language: str = "ko", language_probability: float = 0.99):
        self.language = language
        self.language_probability = language_probability


class FakeWhisperModel:
    """faster-whisper의 model.transcribe()를 흉내냅니다.

    실제 faster-whisper처럼 transcribe() 호출 자체는 즉시 반환하고, 실제 연산은
    반환된 세그먼트 제너레이터를 순회할 때 지연 실행됩니다. hang_seconds가 주어지면
    그 순회 시점에 sleep해 "손상된 오디오에서 STT가 멈춘 상황"을 재현합니다.
    """

    def __init__(
        self,
        hang_seconds: float | None = None,
        error: Exception | None = None,
    ):
        self._hang_seconds = hang_seconds
        self._error = error

    def transcribe(self, audio_path, language, beam_size, vad_filter):
        if self._error is not None:
            raise self._error

        if self._hang_seconds is not None:
            def _hanging_segments():
                time.sleep(self._hang_seconds)
                yield FakeSegment(0.0, 1.0, "이 세그먼트는 타임아웃 전에 나오면 안 됩니다")

            return _hanging_segments(), FakeInfo()

        return iter([FakeSegment(0.0, 1.2, "안녕하세요")]), FakeInfo()


def install_fake_whisper_model(monkeypatch, model: FakeWhisperModel) -> None:
    @contextmanager
    def _fake_whisper_model_context():
        yield model

    monkeypatch.setattr(model_registry, "whisper_model_context", _fake_whisper_model_context)


def create_audio_extraction_result(tmp_path) -> Dict[str, Any]:
    audio_path = tmp_path / "audio.wav"
    audio_path.write_bytes(b"fake wav bytes")
    return {"success": True, "audioPath": str(audio_path)}


def test_transcribe_audio_succeeds_when_whisper_completes_quickly(monkeypatch, tmp_path):
    install_fake_whisper_model(monkeypatch, FakeWhisperModel())

    result = speech_to_text.transcribe_audio(create_audio_extraction_result(tmp_path))

    assert result == {
        "success": True,
        "analysisMethod": "faster_whisper",
        "modelSize": "base",
        "language": "ko",
        "languageProbability": 0.99,
        "transcript": "안녕하세요",
        "segments": [
            {
                "start": 0.0,
                "end": 1.2,
                "duration": 1.2,
                "text": "안녕하세요",
            }
        ],
        "segmentCount": 1,
        "wordCount": 1,
        "error": "",
    }


def test_transcribe_audio_skips_provider_when_audio_extraction_failed(monkeypatch):
    monkeypatch.setattr(
        model_registry,
        "whisper_model_context",
        lambda: pytest.fail("Whisper provider must not be called"),
    )

    result = speech_to_text.transcribe_audio({"success": False, "audioPath": ""})

    assert result["success"] is False
    assert result["error"] == "오디오 추출에 실패하여 STT를 수행하지 못했습니다."


def test_transcribe_audio_skips_provider_when_audio_file_is_missing(monkeypatch, tmp_path):
    monkeypatch.setattr(
        model_registry,
        "whisper_model_context",
        lambda: pytest.fail("Whisper provider must not be called"),
    )

    result = speech_to_text.transcribe_audio(
        {"success": True, "audioPath": str(tmp_path / "missing.wav")}
    )

    assert result["success"] is False
    assert result["error"] == "STT 대상 오디오 파일을 찾을 수 없습니다."


def test_transcribe_audio_maps_provider_error_to_existing_failure_contract(
    monkeypatch,
    tmp_path,
):
    install_fake_whisper_model(
        monkeypatch,
        FakeWhisperModel(error=RuntimeError("provider failed")),
    )

    result = speech_to_text.transcribe_audio(create_audio_extraction_result(tmp_path))

    assert result["success"] is False
    assert result["analysisMethod"] == "faster_whisper"
    assert result["segments"] == []
    assert result["wordCount"] == 0
    assert result["error"] == "provider failed"


def test_transcribe_audio_times_out_instead_of_hanging_forever(monkeypatch, tmp_path):
    monkeypatch.setenv("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", "0.2")
    install_fake_whisper_model(monkeypatch, FakeWhisperModel(hang_seconds=5.0))

    started_at = time.monotonic()
    result = speech_to_text.transcribe_audio(create_audio_extraction_result(tmp_path))
    elapsed_seconds = time.monotonic() - started_at

    assert result["success"] is False
    assert "0.2" in result["error"]
    # 5초짜리 hang이 아니라 0.2초 타임아웃 안에서 실제로 돌아왔는지 확인합니다
    # (넉넉한 여유를 둬 느린 CI 환경에서도 flaky하지 않게 합니다).
    assert elapsed_seconds < 2.0


def test_resolve_whisper_transcribe_timeout_seconds_rejects_invalid_values(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", "not-a-number")

    with pytest.raises(ValueError, match="ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS"):
        speech_to_text.resolve_transcribe_timeout_seconds()


def test_resolve_whisper_transcribe_timeout_seconds_rejects_non_positive_values(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", "0")

    with pytest.raises(ValueError, match="ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS"):
        speech_to_text.resolve_transcribe_timeout_seconds()


def test_resolve_whisper_transcribe_timeout_seconds_defaults_to_600(monkeypatch):
    monkeypatch.delenv("ANALYSIS_ENGINE_WHISPER_TRANSCRIBE_TIMEOUT_SECONDS", raising=False)

    assert speech_to_text.resolve_transcribe_timeout_seconds() == 600.0
