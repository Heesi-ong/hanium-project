import wave
from array import array
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.api import basic_analysis as basic


def point(x: float, y: float) -> SimpleNamespace:
    return SimpleNamespace(x=x, y=y)


def write_pcm16_wav(path: Path, amplitudes: list[int], sample_rate: int = 16000) -> None:
    samples = array("h")
    window_sample_count = int(sample_rate * basic.VOLUME_ANALYSIS_WINDOW_SEC)

    for amplitude in amplitudes:
        samples.extend([amplitude] * window_sample_count)

    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(samples.tobytes())


@pytest.mark.parametrize(
    ("scores", "expected_total"),
    [
        (
            {
                "posture": 80,
                "expression": 70,
                "gaze": 90,
                "speech": 100,
                "gesture": 60,
            },
            83,
        ),
        (
            {
                "posture": 100,
                "expression": 50,
                "gaze": 40,
                "speech": 80,
                "gesture": 20,
            },
            65,
        ),
    ],
)
def test_calculate_score_uses_documented_weights(scores, expected_total):
    # 검출률이 충분하고 STT가 성공한(=패널티 0) 상황을 가정합니다.
    result = basic.calculate_score(
        pose_result={"postureScore": scores["posture"], "detectionRate": 1.0},
        face_result={"gazeScore": scores["gaze"], "detectionRate": 1.0},
        audio_result={
            "speechScore": scores["speech"],
            "analysisMethod": "stt_based_analysis",
            "durationSec": 60,
        },
        gesture_result={"gestureScore": scores["gesture"]},
        emotion_result={"expressionScore": scores["expression"]},
    )

    assert result["totalScore"] == expected_total
    assert result["rawScore"] == expected_total
    assert result["penalty"] == 0
    assert result["postureScore"] == scores["posture"]
    assert result["gazeScore"] == scores["gaze"]
    assert result["speechScore"] == scores["speech"]
    assert result["gestureScore"] == scores["gesture"]
    assert result["expressionScore"] == scores["expression"]
    assert result["reliability"]["lowConfidence"] is False
    assert result["reliability"]["penaltyReasons"] == []


def test_calculate_score_applies_total_penalty_on_low_reliability():
    result = basic.calculate_score(
        pose_result={"postureScore": 80, "detectionRate": 0.3},
        face_result={"gazeScore": 80, "detectionRate": 0.3},
        audio_result={
            "speechScore": 80,
            "analysisMethod": "audio_extracted_duration_based_estimation",
            "durationSec": 5,
        },
        gesture_result={"gestureScore": 80},
        emotion_result={"expressionScore": 80},
    )

    # 가중합 80점, 감점 = 검출률(5+5) + STT 실패(3) + 짧은 영상(5) = 18 → 상한 15점.
    assert result["rawScore"] == 80
    assert result["penalty"] == basic.MAX_TOTAL_PENALTY
    assert result["totalScore"] == 80 - basic.MAX_TOTAL_PENALTY
    assert result["reliability"]["lowConfidence"] is True
    assert len(result["reliability"]["penaltyReasons"]) == 4


def test_calculate_total_penalty_weak_detection_is_mild():
    penalty = basic.calculate_total_penalty(
        pose_result={"detectionRate": 0.6},
        face_result={"detectionRate": 0.6},
        audio_result={"analysisMethod": "stt_based_analysis", "durationSec": 60},
    )

    assert penalty["penalty"] == 4
    assert penalty["lowConfidence"] is False


def test_finalize_speech_score_blends_documented_weights():
    audio_result = {
        "speechSpeedScore": 100,
        "silenceScore": 80,
        "volumeStabilityScore": basic.VOLUME_STABILITY_BASELINE_SCORE,
        "volumeStabilityImplemented": True,
        "speechScore": 0,
    }
    filler_result = {"fillerScore": 60}

    finalized = basic.finalize_speech_score(audio_result, filler_result)

    # 100*0.35 + 80*0.25 + 60*0.25 + 80*0.15 = 35 + 20 + 15 + 12 = 82
    assert finalized["speechScore"] == 82
    assert finalized["fillerScore"] == 60
    assert finalized["volumeStabilityScore"] == basic.VOLUME_STABILITY_BASELINE_SCORE
    assert finalized["volumeStabilityImplemented"] is True


def test_analyze_volume_stability_scores_consistent_non_silent_audio(tmp_path):
    audio_path = tmp_path / "steady.wav"
    write_pcm16_wav(audio_path, [8000, 8000, 8000, 8000])

    result = basic.calculate_volume_stability_from_wav(audio_path)

    assert result["volumeStabilityScore"] == 100
    assert result["volumeStabilityImplemented"] is True
    assert result["volumeStabilityFallbackReason"] == ""
    assert result["volumeRmsDbStdDev"] == 0
    assert result["volumeAnalyzedWindowCount"] == 4
    assert result["volumeSilentWindowCount"] == 0


def test_analyze_volume_stability_penalizes_large_volume_swings(tmp_path):
    audio_path = tmp_path / "swing.wav"
    write_pcm16_wav(audio_path, [1000, 20000, 1000, 20000])

    result = basic.calculate_volume_stability_from_wav(audio_path)

    assert result["volumeStabilityScore"] == 40
    assert result["volumeStabilityImplemented"] is True
    assert result["volumeRmsDbStdDev"] > 10


def test_analyze_volume_stability_falls_back_when_audio_is_missing():
    result = basic.analyze_volume_stability({"success": False, "audioPath": ""})

    assert result == {
        "volumeStabilityScore": basic.VOLUME_STABILITY_BASELINE_SCORE,
        "volumeStabilityImplemented": False,
        "volumeStabilityFallbackReason": "audio_unavailable",
        "volumeRmsDbStdDev": None,
        "volumeAnalyzedWindowCount": 0,
        "volumeSilentWindowCount": 0,
    }


def test_blend_speech_score_clamps_to_valid_range():
    assert basic.blend_speech_score(100, 100, 100, 100) == 100
    assert basic.blend_speech_score(0, 0, 0, 0) == 0


@pytest.mark.parametrize(
    ("shoulder_diff", "expected"),
    [
        (0.0299, 100),
        (0.03, 70),
        (0.0599, 70),
        (0.06, 40),
    ],
)
def test_calculate_shoulder_balance_score_boundaries(shoulder_diff, expected):
    assert basic.calculate_shoulder_balance_score(shoulder_diff) == expected


@pytest.mark.parametrize(
    ("detection_rate", "shoulder_score", "expected"),
    [
        (1.0, 100, 100),
        (0.5, 70, 62),
        (1.5, 100, 100),
        (-1.0, 40, 0),
    ],
)
def test_calculate_posture_score_clamps_weighted_score(detection_rate, shoulder_score, expected):
    assert basic.calculate_posture_score(detection_rate, shoulder_score) == expected


@pytest.mark.parametrize(
    ("gesture_rate", "expected"),
    [
        (0.25, 100),
        (0.65, 100),
        (0.15, 80),
        (0.80, 80),
        (0.05, 60),
        (0.95, 60),
        (0.96, 40),
    ],
)
def test_calculate_gesture_variety_score_boundaries(gesture_rate, expected):
    assert basic.calculate_gesture_variety_score(gesture_rate) == expected


@pytest.mark.parametrize(
    ("movement", "expected"),
    [
        (0.025, 100),
        (0.12, 100),
        (0.01, 80),
        (0.20, 80),
        (0.005, 60),
        (0.30, 60),
        (0.31, 40),
    ],
)
def test_calculate_gesture_movement_score_boundaries(movement, expected):
    assert basic.calculate_gesture_movement_score(movement) == expected


def test_hand_activity_and_wrist_movement_helpers():
    shoulder = {"x": 0.5, "y": 0.5, "visibility": 1.0}
    elbow = {"x": 0.5, "y": 0.6, "visibility": 1.0}
    active_wrist = {"x": 0.7, "y": 0.55, "visibility": 1.0}
    hidden_wrist = {"x": 0.7, "y": 0.55, "visibility": 0.1}

    assert basic.is_hand_active(shoulder, elbow, active_wrist) is True
    assert basic.is_hand_active(shoulder, elbow, hidden_wrist) is False
    assert basic.calculate_wrist_movement(None, active_wrist) is None
    assert basic.calculate_wrist_movement(
        {"x": 0.0, "y": 0.0, "visibility": 1.0},
        {"x": 0.3, "y": 0.4, "visibility": 1.0},
    ) == pytest.approx(0.5)


def test_mouth_eye_and_gaze_ratio_helpers():
    assert basic.calculate_mouth_openness(
        point(0.5, 0.4),
        point(0.5, 0.5),
        point(0.4, 0.45),
        point(0.6, 0.45),
    ) == 0.5
    assert basic.calculate_mouth_openness(
        point(0.5, 0.4),
        point(0.5, 0.5),
        point(0.4, 0.45),
        point(0.4, 0.45),
    ) == 0

    assert basic.calculate_eye_openness(
        point(0.2, 0.2),
        point(0.2, 0.24),
        point(0.8, 0.2),
        point(0.8, 0.24),
        point(0.1, 0.2),
        point(0.9, 0.2),
    ) == 0.05
    assert basic.calculate_eye_openness(
        point(0.2, 0.2),
        point(0.2, 0.24),
        point(0.8, 0.2),
        point(0.8, 0.24),
        point(0.1, 0.2),
        point(0.1, 0.2),
    ) == 0

    assert basic.calculate_iris_gaze_ratio(
        point(0.5, 0.5),
        point(0.3, 0.0),
        point(0.7, 0.0),
        point(0.0, 0.4),
        point(0.0, 0.6),
    ) == {"horizontalRatio": pytest.approx(0.5), "verticalRatio": pytest.approx(0.5)}
    assert basic.calculate_iris_gaze_ratio(
        point(0.5, 0.5),
        point(0.3, 0.0),
        point(0.3, 0.0),
        point(0.0, 0.4),
        point(0.0, 0.6),
    ) is None


def test_gaze_score_and_eye_contact_helpers():
    assert basic.average_gaze_ratios(
        {"horizontalRatio": 0.4, "verticalRatio": 0.6},
        {"horizontalRatio": 0.6, "verticalRatio": 0.4},
    ) == (0.5, 0.5)
    assert basic.average_gaze_ratios(None, None) == (0.5, 0.5)
    assert basic.is_gazing_at_camera(0.5, 0.5) is True
    assert basic.is_gazing_at_camera(0.7, 0.5) is False
    assert basic.calculate_gaze_score_from_ratios(0.5, 0.5) == 100
    assert basic.calculate_gaze_score_from_ratios(0.65, 0.5) == 80
    assert basic.calculate_gaze_score_from_ratios(0.75, 0.5) == 60
    assert basic.calculate_gaze_score_from_ratios(0.9, 0.5) == 40
    assert basic.calculate_gaze_score_from_camera_ratio(0.70) == 90
    assert basic.calculate_gaze_score_from_camera_ratio(0.55) == 74
    assert basic.calculate_gaze_score_from_camera_ratio(0.20) == 29
    assert basic.resolve_eye_contact_level(85) == "good"
    assert basic.resolve_eye_contact_level(70) == "normal"
    assert basic.resolve_eye_contact_level(50) == "weak"
    assert basic.resolve_eye_contact_level(49) == "poor"


def test_expression_helpers():
    assert basic.estimate_emotion_label(0.28, 0.035, 50) == "speaking"
    assert basic.estimate_emotion_label(0.1, 0.03, 80) == "engaged"
    assert basic.estimate_emotion_label(0.1, 0.017, 80) == "low_energy"
    assert basic.estimate_emotion_label(0.1, 0.02, 50) == "neutral"
    assert basic.calculate_expression_score("engaged", 0.1, 0.03, 90) == 92
    assert basic.calculate_expression_score("speaking", 0.3, 0.04, 50) == 75
    assert basic.calculate_expression_score("neutral", 0.1, 0.02, 50) == 70
    assert basic.calculate_expression_score("low_energy", 0.1, 0.01, 50) == 45
    assert basic.calculate_expression_score("unknown", 0.1, 0.02, 50) == 0
    assert basic.calculate_expression_variety_score({"neutral": 1, "engaged": 1, "speaking": 1}) == 100
    assert basic.calculate_expression_variety_score({"neutral": 1, "engaged": 1}) == 80
    assert basic.calculate_expression_variety_score({"unknown": 3}) == 40
    assert basic.resolve_dominant_emotion({"unknown": 4}) == "unknown"
    assert basic.resolve_dominant_emotion({"neutral": 2, "engaged": 5, "unknown": 9}) == "engaged"


def test_speech_speed_silence_and_pause_helpers():
    assert basic.calculate_speech_speed_wpm(130, 60) == 130
    assert basic.calculate_speech_speed_wpm(130, 0) == 0
    assert basic.calculate_speech_speed_score(110) == 100
    assert basic.calculate_speech_speed_score(90) == 80
    assert basic.calculate_speech_speed_score(170) == 80
    assert basic.calculate_speech_speed_score(70) == 60
    assert basic.calculate_speech_speed_score(190) == 60
    assert basic.calculate_speech_speed_score(191) == 40

    assert basic.calculate_silence_ratio(15, 100) == 0.15
    assert basic.calculate_silence_ratio(15, 0) == 0
    assert basic.calculate_silence_score(0.15) == 100
    assert basic.calculate_silence_score(0.25) == 80
    assert basic.calculate_silence_score(0.35) == 60
    assert basic.calculate_silence_score(0.36) == 40

    pauses = basic.analyze_pauses_from_segments(
        segments=[
            {"start": 0, "end": 2},
            {"start": 1, "end": 4},
            {"start": 5.2, "end": 6},
            {"start": 8, "end": 9},
        ],
        total_duration_sec=12,
    )
    assert pauses == {
        "silenceCount": 3,
        "totalSilenceTime": 6.2,
        "silenceRatio": 0.5167,
    }
    assert basic.analyze_pauses_from_segments([], 12) == {
        "silenceCount": 0,
        "totalSilenceTime": 0,
        "silenceRatio": 0,
    }


def test_filler_helpers():
    result = basic.count_filler_words("음 어, 어. 그러니까 발표를 이제 시작합니다")

    assert result["totalCount"] == 5
    assert {item["word"]: item["count"] for item in result["items"]} == {
        "음": 1,
        "어": 2,
        "그러니까": 1,
        "이제": 1,
    }
    assert basic.calculate_filler_ratio(3, 100) == 0.03
    assert basic.calculate_filler_ratio(3, 0) == 0
    assert basic.calculate_filler_score(0.01) == 100
    assert basic.calculate_filler_score(0.03) == 80
    assert basic.calculate_filler_score(0.06) == 60
    assert basic.calculate_filler_score(0.061) == 40


def test_calculate_sample_frame_indexes_handles_empty_and_caps_result_count():
    assert basic.calculate_sample_frame_indexes(0, 100) == []
    assert basic.calculate_sample_frame_indexes(30, 0) == []
    assert basic.calculate_sample_frame_indexes(30, 60) == [0, 30]

    frame_indexes = basic.calculate_sample_frame_indexes(1, 100)

    assert len(frame_indexes) == basic.MAX_EXTRACTED_FRAMES
    assert frame_indexes == sorted(set(frame_indexes))
    assert frame_indexes[0] == 0
    assert frame_indexes[-1] < 100


def test_resolve_video_path_finds_absolute_and_relative_files(tmp_path, monkeypatch):
    video_file = tmp_path / "sample.mp4"
    video_file.write_bytes(b"video")

    assert basic.resolve_video_path(str(video_file)) == video_file.resolve()

    monkeypatch.chdir(tmp_path)

    assert basic.resolve_video_path("sample.mp4") == video_file.resolve()
    assert basic.resolve_video_path("missing.mp4") is None


class FakeDownloadResponse:
    def __init__(self, chunks, status_code=200, headers=None):
        self._chunks = chunks
        self.status_code = status_code
        self.headers = headers or {}
        self.closed = False

    def raise_for_status(self):
        if self.status_code >= 400:
            raise basic.requests.HTTPError(f"status {self.status_code}")

    def iter_content(self, chunk_size):
        for chunk in self._chunks:
            if isinstance(chunk, Exception):
                raise chunk
            yield chunk

    def close(self):
        self.closed = True


def test_download_video_from_url_saves_file_and_returns_path(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    response = FakeDownloadResponse([b"video-bytes"])
    monkeypatch.setattr(
        basic.requests,
        "get",
        lambda url, stream, timeout: response,
    )

    downloaded_path = basic.download_video_from_url(
        "job-download",
        "https://minio.local/uploads/job-download/original.mp4",
        "/storage/uploads/job-download/original.mp4",
    )

    assert downloaded_path is not None
    assert downloaded_path.read_bytes() == b"video-bytes"
    assert downloaded_path.suffix == ".mp4"
    assert response.closed is True


def test_download_video_from_url_returns_none_when_request_fails(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)

    def raise_error(url, stream, timeout):
        raise basic.requests.ConnectionError("connection failed")

    monkeypatch.setattr(basic.requests, "get", raise_error)

    downloaded_path = basic.download_video_from_url(
        "job-download-fail",
        "https://minio.local/uploads/job-download-fail/original.mp4",
        "/storage/uploads/job-download-fail/original.mp4",
    )

    assert downloaded_path is None


def test_download_video_from_url_removes_partial_file_and_closes_response(
    tmp_path,
    monkeypatch,
):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    response = FakeDownloadResponse([
        b"partial-video",
        basic.requests.ConnectionError("stream interrupted"),
    ])
    monkeypatch.setattr(
        basic.requests,
        "get",
        lambda url, stream, timeout: response,
    )

    downloaded_path = basic.download_video_from_url(
        "job-partial-download",
        "https://minio.local/uploads/job-partial-download/original.mp4",
        "/storage/uploads/job-partial-download/original.mp4",
    )

    expected_path = (
        tmp_path / "storage" / "temp" / "job-partial-download" / "download" / "original.mp4"
    )
    assert downloaded_path is None
    assert expected_path.exists() is False
    assert response.closed is True


def test_download_video_from_url_rejects_oversized_content_length(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    monkeypatch.setenv("ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB", "1")
    response = FakeDownloadResponse(
        [b"not-written"],
        headers={"content-length": str(1024 * 1024 + 1)},
    )
    monkeypatch.setattr(
        basic.requests,
        "get",
        lambda url, stream, timeout: response,
    )

    downloaded_path = basic.download_video_from_url(
        "job-oversized-header",
        "https://minio.local/uploads/job-oversized-header/original.mp4",
        "/storage/uploads/job-oversized-header/original.mp4",
    )

    assert downloaded_path is None
    assert response.closed is True


def test_download_video_from_url_rejects_oversized_stream_and_removes_file(
    tmp_path,
    monkeypatch,
):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    monkeypatch.setenv("ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB", "1")
    response = FakeDownloadResponse([b"x" * (1024 * 1024), b"overflow"])
    monkeypatch.setattr(
        basic.requests,
        "get",
        lambda url, stream, timeout: response,
    )

    downloaded_path = basic.download_video_from_url(
        "job-oversized-stream",
        "https://minio.local/uploads/job-oversized-stream/original.mp4",
        "/storage/uploads/job-oversized-stream/original.mp4",
    )

    expected_path = (
        tmp_path / "storage" / "temp" / "job-oversized-stream" / "download" / "original.mp4"
    )
    assert downloaded_path is None
    assert expected_path.exists() is False
    assert response.closed is True


def test_download_video_from_url_rejects_empty_response_and_removes_file(
    tmp_path,
    monkeypatch,
):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    response = FakeDownloadResponse([])
    monkeypatch.setattr(
        basic.requests,
        "get",
        lambda url, stream, timeout: response,
    )

    downloaded_path = basic.download_video_from_url(
        "job-empty-download",
        "https://minio.local/uploads/job-empty-download/original.mp4",
        "/storage/uploads/job-empty-download/original.mp4",
    )

    expected_path = (
        tmp_path / "storage" / "temp" / "job-empty-download" / "download" / "original.mp4"
    )
    assert downloaded_path is None
    assert expected_path.exists() is False
    assert response.closed is True


@pytest.mark.parametrize("configured_value", ["0", "-1", "invalid"])
def test_resolve_analysis_engine_max_video_size_rejects_invalid_values(
    monkeypatch,
    configured_value,
):
    monkeypatch.setenv("ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB", configured_value)

    with pytest.raises(RuntimeError, match="must be a positive integer"):
        basic.resolve_analysis_engine_max_video_size_bytes()


def test_resolve_or_download_video_path_prefers_download_url(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    monkeypatch.setattr(
        basic.requests,
        "get",
        lambda url, stream, timeout: FakeDownloadResponse([b"video-bytes"]),
    )

    resolved_path = basic.resolve_or_download_video_path(
        "job-prefer-url",
        "missing-local-file.mp4",
        "https://minio.local/uploads/job-prefer-url/original.mp4",
    )

    assert resolved_path is not None
    assert resolved_path.read_bytes() == b"video-bytes"


def test_resolve_or_download_video_path_falls_back_to_local_path_when_download_fails(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)

    def raise_error(url, stream, timeout):
        raise basic.requests.ConnectionError("connection failed")

    monkeypatch.setattr(basic.requests, "get", raise_error)

    video_file = tmp_path / "sample.mp4"
    video_file.write_bytes(b"local-video")

    resolved_path = basic.resolve_or_download_video_path(
        "job-fallback",
        str(video_file),
        "https://minio.local/uploads/job-fallback/original.mp4",
    )

    assert resolved_path == video_file.resolve()


def test_resolve_or_download_video_path_uses_local_path_when_no_download_url(tmp_path):
    video_file = tmp_path / "sample.mp4"
    video_file.write_bytes(b"local-video")

    resolved_path = basic.resolve_or_download_video_path(
        "job-no-url",
        str(video_file),
        None,
    )

    assert resolved_path == video_file.resolve()


def test_cleanup_temp_directory_deletes_only_job_directory(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    job_dir = tmp_path / "storage" / "temp" / "job-1"
    job_dir.mkdir(parents=True)
    (job_dir / "frame.jpg").write_bytes(b"frame")

    basic.cleanup_temp_directory("job-1")

    assert not job_dir.exists()


def test_cleanup_temp_directory_rejects_path_traversal(tmp_path, monkeypatch):
    monkeypatch.setattr(basic, "resolve_project_root", lambda: tmp_path)
    outside_dir = tmp_path / "storage" / "outside-job"
    outside_dir.mkdir(parents=True)
    (outside_dir / "keep.txt").write_text("must stay")

    basic.cleanup_temp_directory("../outside-job")

    assert outside_dir.exists()
    assert (outside_dir / "keep.txt").read_text() == "must stay"
