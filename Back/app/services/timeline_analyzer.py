def analyze_timeline_scores(
    video_info: dict,
    frame_result: dict,
    pose_results: list,
    face_results: list
):
    duration = video_info.get("duration_seconds", 0)
    frames = frame_result.get("frames", [])

    if not frames:
        return {
            "timeline_count": 0,
            "timeline": []
        }

    timeline = []

    for index, frame_path in enumerate(frames):
        pose_result = pose_results[index] if index < len(pose_results) else {}
        face_result = face_results[index] if index < len(face_results) else {}

        pose_score = 100 if pose_result.get("pose_detected") is True else None
        face_score = 100 if face_result.get("face_detected") is True else None

        shoulder_score = None
        gaze_score = None

        if pose_result.get("pose_detected") is True:
            landmarks = pose_result.get("landmarks", [])

            left_shoulder = next((lm for lm in landmarks if lm["id"] == 11), None)
            right_shoulder = next((lm for lm in landmarks if lm["id"] == 12), None)

            if left_shoulder and right_shoulder:
                diff = abs(left_shoulder["y"] - right_shoulder["y"])

                if diff < 0.03:
                    shoulder_score = 100
                elif diff < 0.06:
                    shoulder_score = 70
                else:
                    shoulder_score = 40

        if face_result.get("face_detected") is True:
            landmarks = face_result.get("landmarks", [])

            nose = next((lm for lm in landmarks if lm["id"] == 1), None)
            left_eye = next((lm for lm in landmarks if lm["id"] == 33), None)
            right_eye = next((lm for lm in landmarks if lm["id"] == 263), None)

            if nose and left_eye and right_eye:
                eye_center_x = (left_eye["x"] + right_eye["x"]) / 2
                diff = abs(nose["x"] - eye_center_x)

                if diff < 0.02:
                    gaze_score = 100
                elif diff < 0.05:
                    gaze_score = 70
                else:
                    gaze_score = 40

        available_scores = [score for score in (pose_score, shoulder_score, face_score, gaze_score) if score is not None]
        frame_score = round(sum(available_scores) / len(available_scores), 2) if available_scores else None

        timeline.append({
            "time_sec": index,
            "frame_path": frame_path,
            "pose_score": pose_score,
            "shoulder_score": shoulder_score,
            "face_score": face_score,
            "gaze_score": gaze_score,
            "frame_score": frame_score
        })

    return {
        "duration_seconds": duration,
        "timeline_count": len(timeline),
        "timeline": timeline
    }
