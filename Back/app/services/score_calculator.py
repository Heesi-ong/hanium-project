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

            nose = next((lm for lm in landmarks if lm["id"] == 1), None)
            left_eye = next((lm for lm in landmarks if lm["id"] == 33), None)
            right_eye = next((lm for lm in landmarks if lm["id"] == 263), None)

            if nose and left_eye and right_eye:
                eye_center_x = (left_eye["x"] + right_eye["x"]) / 2
                diff = abs(nose["x"] - eye_center_x)

                if diff < 0.02:
                    gaze_scores.append(100)
                elif diff < 0.05:
                    gaze_scores.append(70)
                else:
                    gaze_scores.append(40)

    pose_detection_rate = None
    face_detection_rate = None

    if saved_count > 0 and pose_detected_count > 0:
        pose_detection_rate = round((pose_detected_count / saved_count) * 100, 2)
    if saved_count > 0 and face_detected_count > 0:
        face_detection_rate = round((face_detected_count / saved_count) * 100, 2)

    shoulder_balance_score = None
    if shoulder_balance_scores:
        shoulder_balance_score = round(
            sum(shoulder_balance_scores) / len(shoulder_balance_scores),
            2
        )

    gaze_score = None
    if gaze_scores:
        gaze_score = round(
            sum(gaze_scores) / len(gaze_scores),
            2
        )

    audio_analysis_available = bool(
        audio_result.get("text", "").strip() or audio_result.get("segments")
    )
    speech_speed = audio_result.get("speech_speed_wpm", 0)

    if not audio_analysis_available:
        speech_speed_score = None
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
    gesture_score = gesture_result.get("gesture_score") if pose_detected_count > 0 else None
    gesture_level = gesture_result.get("gesture_level", "UNKNOWN")

    mean_volume_db = volume_result.get("mean_volume_db")
    max_volume_db = volume_result.get("max_volume_db")
    volume_score = volume_result.get("volume_score") if mean_volume_db is not None else None
    volume_level = volume_result.get("volume_level", "UNKNOWN")

    weighted_scores = [
        (pose_detection_rate, 0.07),
        (shoulder_balance_score, 0.15),
        (face_detection_rate, 0.07),
        (gaze_score, 0.15),
        (speech_speed_score, 0.14),
        (silence_score, 0.14),
        (filler_score, 0.09),
        (gesture_score, 0.09),
        (volume_score, 0.10)
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
        "pose_detection_rate": pose_detection_rate is not None,
        "shoulder_balance_score": shoulder_balance_score is not None,
        "face_detection_rate": face_detection_rate is not None,
        "gaze_score": gaze_score is not None,
        "speech_speed_score": speech_speed_score is not None,
        "silence_score": silence_score is not None,
        "filler_score": filler_score is not None,
        "gesture_score": gesture_score is not None,
        "volume_score": volume_score is not None,
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
        "speech_speed_wpm": speech_speed,
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
        "available_score_count": sum(availability.values()),
        "total_score_available": total_score is not None,
    }
