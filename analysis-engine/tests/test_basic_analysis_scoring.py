from pathlib import Path
from types import SimpleNamespace

import pytest

from app.api import basic_analysis as basic


def point(x: float, y: float) -> SimpleNamespace:
    return SimpleNamespace(x=x, y=y)


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
    result = basic.calculate_score(
        pose_result={"postureScore": scores["posture"]},
        face_result={"gazeScore": scores["gaze"]},
        audio_result={"speechScore": scores["speech"]},
        gesture_result={"gestureScore": scores["gesture"]},
        emotion_result={"expressionScore": scores["expression"]},
    )

    assert result == {
        "totalScore": expected_total,
        "postureScore": scores["posture"],
        "gazeScore": scores["gaze"],
        "speechScore": scores["speech"],
        "gestureScore": scores["gesture"],
        "expressionScore": scores["expression"],
    }


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
