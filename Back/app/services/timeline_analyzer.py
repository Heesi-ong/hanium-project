"""프레임별 자세·얼굴·제스처 지표를 시간대별 타임라인 데이터로 변환한다."""

from .face_direction_analyzer import calculate_legacy_gaze_score
from .score_calculator import _meets_visual_threshold


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
    pose_evaluation_available = _meets_visual_threshold(
        sum(item.get("pose_detected") is True for item in pose_results),
        len(frames),
    )
    face_evaluation_available = _meets_visual_threshold(
        sum(item.get("face_detected") is True for item in face_results),
        len(frames),
    )

    for index, frame_path in enumerate(frames):
        pose_result = pose_results[index] if index < len(pose_results) else {}
        face_result = face_results[index] if index < len(face_results) else {}

        pose_score = 100 if pose_result.get("pose_detected") is True else None
        face_score = 100 if face_result.get("face_detected") is True else None

        shoulder_score = None
        gaze_score = None
        head_direction_score = None
        yaw_degrees = None
        pitch_degrees = None
        roll_degrees = None

        if pose_evaluation_available and pose_result.get("pose_detected") is True:
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

        if face_evaluation_available and face_result.get("face_detected") is True:
            landmarks = face_result.get("landmarks", [])

            gaze_score = calculate_legacy_gaze_score(landmarks)
            head_direction_score = face_result.get("head_direction_score")
            yaw_degrees = face_result.get("yaw_degrees")
            pitch_degrees = face_result.get("pitch_degrees")
            roll_degrees = face_result.get("roll_degrees")

        available_scores = [score for score in (shoulder_score, gaze_score) if score is not None]
        frame_score = round(sum(available_scores) / len(available_scores), 2) if available_scores else None

        timeline.append({
            "time_sec": index,
            "frame_path": frame_path,
            "pose_score": pose_score,
            "shoulder_score": shoulder_score,
            "face_score": face_score,
            "gaze_score": gaze_score,
            "head_direction_score": head_direction_score,
            "yaw_degrees": yaw_degrees,
            "pitch_degrees": pitch_degrees,
            "roll_degrees": roll_degrees,
            "frame_score": frame_score
        })

    return {
        "duration_seconds": duration,
        "timeline_count": len(timeline),
        "timeline": timeline
    }
