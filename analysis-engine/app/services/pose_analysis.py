from typing import Any, Dict, List

import mediapipe as mp

from app.core import model_registry
from app.services import scoring

LEFT_SHOULDER_INDEX = 11
RIGHT_SHOULDER_INDEX = 12
LEFT_ELBOW_INDEX = 13
RIGHT_ELBOW_INDEX = 14
LEFT_WRIST_INDEX = 15
RIGHT_WRIST_INDEX = 16


def analyze_pose_from_frames(
    sampled_frames: List[Dict[str, Any]],
    mp_images: List[mp.Image | None],
) -> Dict[str, Any]:
    if not sampled_frames:
        return create_empty_pose_result()

    analyzed_frames: List[Dict[str, Any]] = []
    detected_count = 0
    shoulder_balance_scores: List[int] = []
    shoulder_diffs: List[float] = []

    with model_registry.pose_landmarker_context() as landmarker:
        for frame_index, frame_info in enumerate(sampled_frames):
            mp_image = mp_images[frame_index] if frame_index < len(mp_images) else None
            if mp_image is None:
                analyzed_frames.append(create_pose_frame_result(frame_info, False))
                continue

            result = landmarker.detect(mp_image)

            if not result.pose_landmarks:
                analyzed_frames.append(create_pose_frame_result(frame_info, False))
                continue

            landmarks = result.pose_landmarks[0]

            if len(landmarks) <= RIGHT_WRIST_INDEX:
                analyzed_frames.append(create_pose_frame_result(frame_info, False))
                continue

            detected_count += 1

            left_shoulder = landmarks[LEFT_SHOULDER_INDEX]
            right_shoulder = landmarks[RIGHT_SHOULDER_INDEX]
            left_elbow = landmarks[LEFT_ELBOW_INDEX]
            right_elbow = landmarks[RIGHT_ELBOW_INDEX]
            left_wrist = landmarks[LEFT_WRIST_INDEX]
            right_wrist = landmarks[RIGHT_WRIST_INDEX]

            shoulder_diff = abs(left_shoulder.y - right_shoulder.y)
            shoulder_score = calculate_shoulder_balance_score(shoulder_diff)

            shoulder_diffs.append(shoulder_diff)
            shoulder_balance_scores.append(shoulder_score)

            analyzed_frames.append(
                {
                    "sequence": frame_info.get("sequence"),
                    "timestampSec": frame_info.get("timestampSec"),
                    "poseDetected": True,
                    "leftShoulder": create_landmark_dict(left_shoulder),
                    "rightShoulder": create_landmark_dict(right_shoulder),
                    "leftElbow": create_landmark_dict(left_elbow),
                    "rightElbow": create_landmark_dict(right_elbow),
                    "leftWrist": create_landmark_dict(left_wrist),
                    "rightWrist": create_landmark_dict(right_wrist),
                    "shoulderDiff": round(shoulder_diff, 4),
                    "shoulderBalanceScore": shoulder_score,
                }
            )

    total_frames = len(sampled_frames)
    detection_rate = round(detected_count / total_frames, 4) if total_frames > 0 else 0

    average_shoulder_score = scoring.calculate_average_int(shoulder_balance_scores)
    average_shoulder_diff = scoring.calculate_average_float(shoulder_diffs)

    posture_score = calculate_posture_score(
        detection_rate=detection_rate,
        shoulder_balance_score=average_shoulder_score,
    )

    return {
        "analysisMethod": "mediapipe_tasks_pose_landmarker",
        "detectionRate": detection_rate,
        "detectedFrameCount": detected_count,
        "totalFrameCount": total_frames,
        "postureScore": posture_score,
        "shoulderBalanceScore": average_shoulder_score,
        "averageShoulderDiff": average_shoulder_diff,
        "frameResults": analyzed_frames,
    }


def create_landmark_dict(landmark: Any) -> Dict[str, float]:
    return {
        "x": round(float(getattr(landmark, "x", 0)), 4),
        "y": round(float(getattr(landmark, "y", 0)), 4),
        "visibility": round(float(getattr(landmark, "visibility", 1)), 4),
        "presence": round(float(getattr(landmark, "presence", 1)), 4),
    }


def create_pose_frame_result(
    frame_info: Dict[str, Any],
    detected: bool,
) -> Dict[str, Any]:
    return {
        "sequence": frame_info.get("sequence"),
        "timestampSec": frame_info.get("timestampSec"),
        "poseDetected": detected,
        "shoulderDiff": None,
        "shoulderBalanceScore": 0,
    }


def analyze_gesture_from_pose_result(pose_result: Dict[str, Any]) -> Dict[str, Any]:
    frame_results = pose_result.get("frameResults", [])

    if not frame_results:
        return create_empty_gesture_result()

    gesture_frames: List[Dict[str, Any]] = []
    active_count = 0
    visible_hand_count = 0
    movement_distances: List[float] = []

    previous_left_wrist = None
    previous_right_wrist = None

    for frame_result in frame_results:
        if not frame_result.get("poseDetected"):
            gesture_frames.append(create_gesture_frame_result(frame_result, False))
            continue

        left_shoulder = frame_result.get("leftShoulder")
        right_shoulder = frame_result.get("rightShoulder")
        left_elbow = frame_result.get("leftElbow")
        right_elbow = frame_result.get("rightElbow")
        left_wrist = frame_result.get("leftWrist")
        right_wrist = frame_result.get("rightWrist")

        if not all(
            [
                left_shoulder,
                right_shoulder,
                left_elbow,
                right_elbow,
                left_wrist,
                right_wrist,
            ]
        ):
            gesture_frames.append(create_gesture_frame_result(frame_result, False))
            continue

        left_hand_visible = left_wrist.get("visibility", 0) >= 0.5
        right_hand_visible = right_wrist.get("visibility", 0) >= 0.5

        if left_hand_visible:
            visible_hand_count += 1

        if right_hand_visible:
            visible_hand_count += 1

        left_hand_active = is_hand_active(
            shoulder=left_shoulder,
            elbow=left_elbow,
            wrist=left_wrist,
        )

        right_hand_active = is_hand_active(
            shoulder=right_shoulder,
            elbow=right_elbow,
            wrist=right_wrist,
        )

        left_movement = calculate_wrist_movement(previous_left_wrist, left_wrist)
        right_movement = calculate_wrist_movement(previous_right_wrist, right_wrist)

        if left_movement is not None:
            movement_distances.append(left_movement)

        if right_movement is not None:
            movement_distances.append(right_movement)

        gesture_active = left_hand_active or right_hand_active

        if gesture_active:
            active_count += 1

        gesture_frames.append(
            {
                "sequence": frame_result.get("sequence"),
                "timestampSec": frame_result.get("timestampSec"),
                "gestureDetected": gesture_active,
                "leftHandVisible": left_hand_visible,
                "rightHandVisible": right_hand_visible,
                "leftHandActive": left_hand_active,
                "rightHandActive": right_hand_active,
                "leftWristMovement": round(left_movement, 4)
                if left_movement is not None
                else None,
                "rightWristMovement": round(right_movement, 4)
                if right_movement is not None
                else None,
            }
        )

        previous_left_wrist = left_wrist
        previous_right_wrist = right_wrist

    total_pose_frames = len(frame_results)
    gesture_rate = (
        round(active_count / total_pose_frames, 4) if total_pose_frames > 0 else 0
    )
    hand_visibility_rate = (
        round(
            visible_hand_count / (total_pose_frames * 2),
            4,
        )
        if total_pose_frames > 0
        else 0
    )

    average_movement = scoring.calculate_average_float(movement_distances)
    gesture_variety_score = calculate_gesture_variety_score(gesture_rate)
    hand_visibility_score = int(hand_visibility_rate * 100)
    gesture_movement_score = calculate_gesture_movement_score(average_movement)

    gesture_score = int(
        gesture_variety_score * 0.45
        + hand_visibility_score * 0.25
        + gesture_movement_score * 0.30
    )

    gesture_score = max(0, min(gesture_score, 100))

    return {
        "analysisMethod": "mediapipe_tasks_pose_landmarker_wrist_elbow_based",
        "gestureScore": gesture_score,
        "gestureRate": gesture_rate,
        "gestureFrameCount": active_count,
        "totalFrameCount": total_pose_frames,
        "handVisibilityRate": hand_visibility_rate,
        "averageWristMovement": average_movement,
        "gestureVarietyScore": gesture_variety_score,
        "handVisibilityScore": hand_visibility_score,
        "gestureMovementScore": gesture_movement_score,
        "frameResults": gesture_frames,
        "note": "MediaPipe Tasks PoseLandmarker의 손목, 팔꿈치, 어깨 위치를 기반으로 제스처를 추정했습니다.",
    }


def is_hand_active(
    shoulder: Dict[str, float],
    elbow: Dict[str, float],
    wrist: Dict[str, float],
) -> bool:
    wrist_visible = wrist.get("visibility", 0) >= 0.5
    elbow_visible = elbow.get("visibility", 0) >= 0.5

    if not wrist_visible or not elbow_visible:
        return False

    wrist_above_elbow = wrist.get("y", 1) < elbow.get("y", 1)
    wrist_near_or_above_shoulder = wrist.get("y", 1) < shoulder.get("y", 1) + 0.12
    arm_extended = abs(wrist.get("x", 0) - shoulder.get("x", 0)) > 0.12

    return wrist_above_elbow or wrist_near_or_above_shoulder or arm_extended


def calculate_wrist_movement(
    previous_wrist: Dict[str, float] | None,
    current_wrist: Dict[str, float],
) -> float | None:
    if previous_wrist is None:
        return None

    if (
        previous_wrist.get("visibility", 0) < 0.5
        or current_wrist.get("visibility", 0) < 0.5
    ):
        return None

    x_diff = current_wrist.get("x", 0) - previous_wrist.get("x", 0)
    y_diff = current_wrist.get("y", 0) - previous_wrist.get("y", 0)

    return (x_diff**2 + y_diff**2) ** 0.5


def calculate_gesture_variety_score(gesture_rate: float) -> int:
    if 0.25 <= gesture_rate <= 0.65:
        return 100

    if 0.15 <= gesture_rate < 0.25:
        return 80

    if 0.65 < gesture_rate <= 0.80:
        return 80

    if 0.05 <= gesture_rate < 0.15:
        return 60

    if 0.80 < gesture_rate <= 0.95:
        return 60

    return 40


def calculate_gesture_movement_score(average_movement: float) -> int:
    if 0.025 <= average_movement <= 0.12:
        return 100

    if 0.01 <= average_movement < 0.025:
        return 80

    if 0.12 < average_movement <= 0.20:
        return 80

    if 0.005 <= average_movement < 0.01:
        return 60

    if 0.20 < average_movement <= 0.30:
        return 60

    return 40


def create_gesture_frame_result(
    frame_result: Dict[str, Any],
    detected: bool,
) -> Dict[str, Any]:
    return {
        "sequence": frame_result.get("sequence"),
        "timestampSec": frame_result.get("timestampSec"),
        "gestureDetected": detected,
        "leftHandVisible": False,
        "rightHandVisible": False,
        "leftHandActive": False,
        "rightHandActive": False,
        "leftWristMovement": None,
        "rightWristMovement": None,
    }


def calculate_shoulder_balance_score(shoulder_diff: float) -> int:
    if shoulder_diff < 0.03:
        return 100

    if shoulder_diff < 0.06:
        return 70

    return 40


def calculate_posture_score(
    detection_rate: float,
    shoulder_balance_score: int,
) -> int:
    detection_score = int(detection_rate * 100)
    posture_score = int(detection_score * 0.4 + shoulder_balance_score * 0.6)
    return max(0, min(posture_score, 100))


def create_empty_pose_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "mediapipe_tasks_pose_landmarker",
        "detectionRate": 0,
        "detectedFrameCount": 0,
        "totalFrameCount": 0,
        "postureScore": 0,
        "shoulderBalanceScore": 0,
        "averageShoulderDiff": 0,
        "frameResults": [],
    }


def create_empty_gesture_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "mediapipe_tasks_pose_landmarker_wrist_elbow_based",
        "gestureScore": 0,
        "gestureRate": 0,
        "gestureFrameCount": 0,
        "totalFrameCount": 0,
        "handVisibilityRate": 0,
        "averageWristMovement": 0,
        "gestureVarietyScore": 0,
        "handVisibilityScore": 0,
        "gestureMovementScore": 0,
        "frameResults": [],
        "note": "포즈 프레임 결과가 없어 제스처 분석을 수행하지 못했습니다.",
    }
