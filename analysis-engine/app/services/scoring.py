from typing import Any, Dict, List

POSE_LOW_DETECTION_THRESHOLD = 0.5
POSE_WEAK_DETECTION_THRESHOLD = 0.7
FACE_LOW_DETECTION_THRESHOLD = 0.5
FACE_WEAK_DETECTION_THRESHOLD = 0.7
SHORT_VIDEO_THRESHOLD_SEC = 10
MAX_TOTAL_PENALTY = 15


def calculate_score(
    pose_result: Dict[str, Any],
    face_result: Dict[str, Any],
    audio_result: Dict[str, Any],
    gesture_result: Dict[str, Any],
    emotion_result: Dict[str, Any],
) -> Dict[str, Any]:
    # '발표_코칭_점수화_알고리즘_선정_자료'의 "9. 최종 점수화" 기준입니다.
    # 자세 25% + 표정 20% + 시선 20% + 음성 25% + 제스처 10%
    posture_score = int(pose_result.get("postureScore", 0))
    gaze_score = int(face_result.get("gazeScore", 0))
    speech_score = int(audio_result.get("speechScore", 0))
    gesture_score = int(gesture_result.get("gestureScore", 0))
    expression_score = int(emotion_result.get("expressionScore", 0))

    weighted_score = int(
        posture_score * 0.25
        + expression_score * 0.20
        + gaze_score * 0.20
        + speech_score * 0.25
        + gesture_score * 0.10
    )

    penalty_result = calculate_total_penalty(
        pose_result=pose_result,
        face_result=face_result,
        audio_result=audio_result,
    )
    penalty = penalty_result["penalty"]
    total_score = max(0, min(weighted_score - penalty, 100))

    return {
        "totalScore": total_score,
        "rawScore": weighted_score,
        "penalty": penalty,
        "postureScore": posture_score,
        "gazeScore": gaze_score,
        "speechScore": speech_score,
        "gestureScore": gesture_score,
        "expressionScore": expression_score,
        "reliability": {
            "poseDetectionRate": penalty_result["poseDetectionRate"],
            "faceDetectionRate": penalty_result["faceDetectionRate"],
            "lowConfidence": penalty_result["lowConfidence"],
            "penaltyReasons": penalty_result["reasons"],
        },
    }


def calculate_total_penalty(
    pose_result: Dict[str, Any],
    face_result: Dict[str, Any],
    audio_result: Dict[str, Any],
) -> Dict[str, Any]:
    """Calculate the bounded reliability penalty without changing component scores."""
    reasons: List[str] = []
    penalty = 0

    pose_detection_rate = float(pose_result.get("detectionRate", 0))
    face_detection_rate = float(face_result.get("detectionRate", 0))
    duration_sec = float(audio_result.get("durationSec", 0))
    audio_method = str(audio_result.get("analysisMethod", ""))

    if pose_detection_rate < POSE_LOW_DETECTION_THRESHOLD:
        penalty += 5
        reasons.append("자세 검출률이 50% 미만입니다.")
    elif pose_detection_rate < POSE_WEAK_DETECTION_THRESHOLD:
        penalty += 2
        reasons.append("자세 검출률이 70% 미만입니다.")

    if face_detection_rate < FACE_LOW_DETECTION_THRESHOLD:
        penalty += 5
        reasons.append("얼굴 검출률이 50% 미만입니다.")
    elif face_detection_rate < FACE_WEAK_DETECTION_THRESHOLD:
        penalty += 2
        reasons.append("얼굴 검출률이 70% 미만입니다.")

    if audio_method and audio_method != "stt_based_analysis":
        penalty += 3
        reasons.append("STT에 실패해 음성 추정값을 사용했습니다.")

    if 0 < duration_sec < SHORT_VIDEO_THRESHOLD_SEC:
        penalty += 5
        reasons.append("영상이 너무 짧아 분석 신뢰도가 낮습니다.")

    penalty = min(penalty, MAX_TOTAL_PENALTY)
    low_confidence = (
        pose_detection_rate < POSE_LOW_DETECTION_THRESHOLD
        or face_detection_rate < FACE_LOW_DETECTION_THRESHOLD
        or penalty >= 8
    )

    return {
        "penalty": penalty,
        "reasons": reasons,
        "lowConfidence": low_confidence,
        "poseDetectionRate": round(pose_detection_rate, 4),
        "faceDetectionRate": round(face_detection_rate, 4),
    }


def calculate_average_int(values: List[int]) -> int:
    if not values:
        return 0

    return int(sum(values) / len(values))


def calculate_average_float(values: List[float]) -> float:
    if not values:
        return 0

    return round(sum(values) / len(values), 4)
