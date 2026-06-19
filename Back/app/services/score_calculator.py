"""측정 가능한 발표 지표를 결정론적 규칙으로 점수화하고 종합 점수를 계산한다."""

import math

from .face_direction_analyzer import calculate_legacy_gaze_score

MIN_VISUAL_VALID_FRAMES = 3
MIN_VISUAL_DETECTION_RATE = 30


def _meets_visual_threshold(detected_count, saved_count):
    required_count = max(MIN_VISUAL_VALID_FRAMES, math.ceil(saved_count * MIN_VISUAL_DETECTION_RATE / 100))
    return saved_count > 0 and detected_count >= required_count


def calculate_basic_score(
    video_info: dict,
    frame_result: dict,
    pose_results: list,
    face_results: list,
    audio_result: dict,
    gesture_result: dict = None,
    volume_result: dict = None
):
    duration = video_info.get("duration_seconds", 0)
    saved_count = frame_result.get("saved_count", 0)

    pose_detected_count = 0
    face_detected_count = 0

    shoulder_balance_scores = []
    gaze_scores = []
    head_direction_scores = []

    if gesture_result is None:
        gesture_result = {}

    if volume_result is None:
        volume_result = {}

    for item in pose_results:
        if item.get("pose_detected") is True:
            pose_detected_count += 1

            landmarks = item.get("landmarks", [])

            left_shoulder = next((lm for lm in landmarks if lm["id"] == 11), None)
            right_shoulder = next((lm for lm in landmarks if lm["id"] == 12), None)

            if left_shoulder and right_shoulder:
                diff = abs(left_shoulder["y"] - right_shoulder["y"])

                if diff < 0.03:
                    shoulder_balance_scores.append(100)
                elif diff < 0.06:
                    shoulder_balance_scores.append(70)
                else:
                    shoulder_balance_scores.append(40)

    for item in face_results:
        if item.get("face_detected") is True:
            face_detected_count += 1

            landmarks = item.get("landmarks", [])

            gaze_score = calculate_legacy_gaze_score(landmarks)
            if gaze_score is not None:
                gaze_scores.append(gaze_score)

            head_direction_score = item.get("head_direction_score")
            if isinstance(head_direction_score, (int, float)):
                head_direction_scores.append(head_direction_score)

    pose_detection_rate = None
    face_detection_rate = None

    if saved_count > 0:
        pose_detection_rate = round((pose_detected_count / saved_count) * 100, 2)
    if saved_count > 0:
        face_detection_rate = round((face_detected_count / saved_count) * 100, 2)

    pose_evaluation_available = _meets_visual_threshold(pose_detected_count, saved_count)
    face_evaluation_available = _meets_visual_threshold(face_detected_count, saved_count)

    shoulder_balance_score = None
    if pose_evaluation_available and len(shoulder_balance_scores) >= MIN_VISUAL_VALID_FRAMES:
        shoulder_balance_score = round(
            sum(shoulder_balance_scores) / len(shoulder_balance_scores),
            2
        )

    gaze_score = None
    if face_evaluation_available and len(gaze_scores) >= MIN_VISUAL_VALID_FRAMES:
        gaze_score = round(
            sum(gaze_scores) / len(gaze_scores),
            2
        )

    head_direction_score = None
    if face_evaluation_available and len(head_direction_scores) >= MIN_VISUAL_VALID_FRAMES:
        head_direction_score = round(
            sum(head_direction_scores) / len(head_direction_scores),
            2
        )

    audio_analysis_available = bool(
        audio_result.get("text", "").strip() or audio_result.get("segments")
    )
    speech_speed = audio_result.get("speech_speed_wpm", 0)
    speech_speed_spm = audio_result.get("speech_speed_spm")

    if not audio_analysis_available:
        speech_speed_score = None
    elif isinstance(speech_speed_spm, (int, float)) and speech_speed_spm > 0:
        if 250 <= speech_speed_spm <= 400:
            speech_speed_score = 100
        elif 180 <= speech_speed_spm < 250 or 400 < speech_speed_spm <= 500:
            speech_speed_score = 70
        else:
            speech_speed_score = 40
    elif 100 <= speech_speed <= 160:
        speech_speed_score = 100
    elif 80 <= speech_speed < 100 or 160 < speech_speed <= 190:
        speech_speed_score = 70
    elif speech_speed > 0:
        speech_speed_score = 40
    else:
        speech_speed_score = 0

    silence_count = audio_result.get("silence_count", 0)
    total_silence_time = audio_result.get("total_silence_time", 0)

    if not audio_analysis_available:
        silence_score = None
    elif silence_count <= 2 and total_silence_time <= 5:
        silence_score = 100
    elif silence_count <= 5 and total_silence_time <= 12:
        silence_score = 70
    else:
        silence_score = 40

    filler_count = audio_result.get("filler_count", 0)
    filler_words = audio_result.get("filler_words", {})
    filler_score = (
        audio_result.get("filler_score", 100)
        if audio_analysis_available
        else None
    )

    gesture_movement_count = gesture_result.get("gesture_movement_count", 0)
    gesture_score = gesture_result.get("gesture_score") if pose_evaluation_available else None
    gesture_level = gesture_result.get("gesture_level", "UNKNOWN")

    mean_volume_db = volume_result.get("mean_volume_db")
    max_volume_db = volume_result.get("max_volume_db")
    volume_score = volume_result.get("volume_score") if mean_volume_db is not None else None
    volume_level = volume_result.get("volume_level", "UNKNOWN")

    weighted_scores = [
        (shoulder_balance_score, 0.18),
        (gaze_score, 0.18),
        (speech_speed_score, 0.16),
        (silence_score, 0.16),
        (filler_score, 0.11),
        (gesture_score, 0.10),
        (volume_score, 0.11),
    ]
    available_scores = [
        (score, weight)
        for score, weight in weighted_scores
        if score is not None
    ]
    total_weight = sum(weight for _, weight in available_scores)
    total_score = round(
        sum(score * weight for score, weight in available_scores) / total_weight,
        2
    ) if total_weight else None

    availability = {
        "shoulder_balance_score": shoulder_balance_score is not None,
        "gaze_score": gaze_score is not None,
        "speech_speed_score": speech_speed_score is not None,
        "silence_score": silence_score is not None,
        "filler_score": filler_score is not None,
        "gesture_score": gesture_score is not None,
        "volume_score": volume_score is not None,
    }
    confidence_availability = {
        "pose_detection_rate": pose_detection_rate is not None,
        "face_detection_rate": face_detection_rate is not None,
    }
    analysis_confidence = {
        "visual": {
            "pose_detection_rate": pose_detection_rate,
            "face_detection_rate": face_detection_rate,
            "minimum_valid_frames": MIN_VISUAL_VALID_FRAMES,
            "minimum_detection_rate": MIN_VISUAL_DETECTION_RATE,
            "pose_evaluation_available": pose_evaluation_available,
            "face_evaluation_available": face_evaluation_available,
            "head_direction_valid_frames": len(head_direction_scores),
            "head_direction_evaluation_available": head_direction_score is not None,
            "level": (
                "high"
                if pose_evaluation_available
                and face_evaluation_available
                and pose_detection_rate >= 70
                and face_detection_rate >= 70
                else (
                    "moderate"
                    if pose_evaluation_available and face_evaluation_available
                    else "limited"
                )
            ),
        },
        "audio": {
            "available": audio_analysis_available,
            "level": "high" if audio_analysis_available else "limited",
        },
    }

    return {
        "total_score": total_score,
        "duration_seconds": duration,
        "saved_frame_count": saved_count,
        "pose_detected_count": pose_detected_count,
        "pose_detection_rate": pose_detection_rate,
        "face_detected_count": face_detected_count,
        "face_detection_rate": face_detection_rate,
        "shoulder_balance_score": shoulder_balance_score,
        "gaze_score": gaze_score,
        "head_direction_score": head_direction_score,
        "head_direction_valid_frames": len(head_direction_scores),
        "speech_speed_wpm": speech_speed,
        "speech_speed_spm": speech_speed_spm,
        "audio_analysis_available": audio_analysis_available,
        "speech_speed_score": speech_speed_score,
        "silence_count": silence_count,
        "total_silence_time": total_silence_time,
        "silence_score": silence_score,
        "filler_count": filler_count,
        "filler_words": filler_words,
        "filler_score": filler_score,
        "gesture_movement_count": gesture_movement_count,
        "gesture_score": gesture_score,
        "gesture_level": gesture_level,
        "mean_volume_db": mean_volume_db,
        "max_volume_db": max_volume_db,
        "volume_score": volume_score,
        "volume_level": volume_level,
        "score_availability": availability,
        "confidence_availability": confidence_availability,
        "analysis_confidence": analysis_confidence,
        "available_score_count": sum(availability.values()),
        "total_score_available": total_score is not None,
    }
