from typing import Any, Dict, List

POSE_LOW_DETECTION_THRESHOLD = 0.5
POSE_WEAK_DETECTION_THRESHOLD = 0.7
FACE_LOW_DETECTION_THRESHOLD = 0.5
FACE_WEAK_DETECTION_THRESHOLD = 0.7
SHORT_VIDEO_THRESHOLD_SEC = 10
MAX_TOTAL_PENALTY = 15
SCORE_FORMULA_VERSION = "weighted-v1"
SCORE_WEIGHTS = {
    "postureScore": 0.25,
    "expressionScore": 0.20,
    "gazeScore": 0.20,
    "speechScore": 0.25,
    "gestureScore": 0.10,
}


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

    component_scores = {
        "postureScore": posture_score,
        "expressionScore": expression_score,
        "gazeScore": gaze_score,
        "speechScore": speech_score,
        "gestureScore": gesture_score,
    }
    # weighted_score_before_rounding은 반드시 기존 코드와 완전히 동일한 하나의 float 표현식으로
    # 계산해야 합니다. 항목별로 round(x, 4) 한 뒤 합산하면 0.20/0.10처럼 이진수로 정확히 표현되지
    # 않는 가중치의 반올림 오차가 누적되어, 원래 한 번에 합산하던 값과 정수부가 달라지는 경우가
    # 실측으로 확인됐습니다(2백만 건 무작위 대입 중 1611건, 약 0.08%). weightedContributions는
    # 설명용 항목별 분해값이라 개별 반올림해도 괜찮지만, 실제 total/rawScore에 쓰이는
    # weighted_score는 이 분해값의 합이 아니라 기존과 같은 단일 표현식이어야 합니다.
    weighted_score_before_rounding = (
        posture_score * 0.25
        + expression_score * 0.20
        + gaze_score * 0.20
        + speech_score * 0.25
        + gesture_score * 0.10
    )
    # 기존 점수 계약을 유지합니다. Python int(float)는 0 방향으로 버립니다.
    weighted_score = int(weighted_score_before_rounding)

    weighted_contributions = {
        key: round(component_scores[key] * weight, 4)
        for key, weight in SCORE_WEIGHTS.items()
    }

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
        "explanation": {
            "formulaVersion": SCORE_FORMULA_VERSION,
            "formula": "clamp(int(sum(componentScore * weight)) - penalty, 0, 100)",
            "weights": SCORE_WEIGHTS.copy(),
            "weightedContributions": weighted_contributions,
            "weightedScoreBeforeRounding": round(weighted_score_before_rounding, 4),
            "roundingPolicy": "truncate_toward_zero",
            "rawScore": weighted_score,
            "penaltyApplied": penalty,
            "penaltyReasons": list(penalty_result["reasons"]),
            "clampRange": {"min": 0, "max": 100},
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
