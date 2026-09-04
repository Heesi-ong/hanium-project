import statistics
from typing import Any, Dict, List

POSE_LOW_DETECTION_THRESHOLD = 0.5
POSE_WEAK_DETECTION_THRESHOLD = 0.7
SHORT_VIDEO_THRESHOLD_SEC = 10
MAX_TOTAL_PENALTY = 13
SCORE_FORMULA_VERSION = "weighted-v2"
SCORE_WEIGHTS = {
    "postureScore": 5 / 12,
    "speechScore": 5 / 12,
    "gestureScore": 1 / 6,
}


def calculate_score(
    pose_result: Dict[str, Any],
    face_result: Dict[str, Any],
    audio_result: Dict[str, Any],
    gesture_result: Dict[str, Any],
    emotion_result: Dict[str, Any],
) -> Dict[str, Any]:
    # 시선 및 표정 검출은 사용자 점수에서 제외합니다. 기존 산식에서 남은
    # 자세(25), 음성(25), 제스처(10)의 상대 비중을 100%로 정규화했습니다.
    # 자세 5/12 + 음성 5/12 + 제스처 1/6
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
    # 실제 점수는 설명용 개별 기여도를 반올림해 합산하지 않고, 하나의 표현식으로 계산한 뒤
    # 기존 계약대로 0 방향 절삭합니다.
    weighted_score_before_rounding = (
        posture_score * SCORE_WEIGHTS["postureScore"]
        + speech_score * SCORE_WEIGHTS["speechScore"]
        + gesture_score * SCORE_WEIGHTS["gestureScore"]
    )
    # 기존 점수 계약을 유지합니다. Python int(float)는 0 방향으로 버립니다.
    weighted_score = int(weighted_score_before_rounding)

    weighted_contributions = {
        key: round(component_scores[key] * weight, 4)
        for key, weight in SCORE_WEIGHTS.items()
    }

    penalty_result = calculate_total_penalty(
        pose_result=pose_result,
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
    audio_result: Dict[str, Any],
) -> Dict[str, Any]:
    """Calculate the bounded reliability penalty without changing component scores."""
    reasons: List[str] = []
    penalty = 0

    pose_detection_rate = float(pose_result.get("detectionRate", 0))
    duration_sec = float(audio_result.get("durationSec", 0))
    audio_method = str(audio_result.get("analysisMethod", ""))

    if pose_detection_rate < POSE_LOW_DETECTION_THRESHOLD:
        penalty += 5
        reasons.append("자세 검출률이 50% 미만입니다.")
    elif pose_detection_rate < POSE_WEAK_DETECTION_THRESHOLD:
        penalty += 2
        reasons.append("자세 검출률이 70% 미만입니다.")

    if audio_method and audio_method != "stt_based_analysis":
        penalty += 3
        reasons.append("STT에 실패해 음성 추정값을 사용했습니다.")

    if 0 < duration_sec < SHORT_VIDEO_THRESHOLD_SEC:
        penalty += 5
        reasons.append("영상이 너무 짧아 분석 신뢰도가 낮습니다.")

    penalty = min(penalty, MAX_TOTAL_PENALTY)
    low_confidence = (
        pose_detection_rate < POSE_LOW_DETECTION_THRESHOLD
        or penalty >= 8
    )

    return {
        "penalty": penalty,
        "reasons": reasons,
        "lowConfidence": low_confidence,
        "poseDetectionRate": round(pose_detection_rate, 4),
    }


def calculate_average_int(values: List[int]) -> int:
    if not values:
        return 0

    return int(statistics.fmean(values))


def calculate_average_float(values: List[float]) -> float:
    if not values:
        return 0

    return round(statistics.fmean(values), 4)
