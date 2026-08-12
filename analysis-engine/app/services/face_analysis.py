from typing import Any, Dict, List

import mediapipe as mp

from app.core import model_registry
from app.services import scoring

LEFT_EYE_OUTER_INDEX = 33
LEFT_EYE_INNER_INDEX = 133
RIGHT_EYE_INNER_INDEX = 362
RIGHT_EYE_OUTER_INDEX = 263
NOSE_TIP_INDEX = 1

LEFT_EYE_TOP_INDEX = 159
LEFT_EYE_BOTTOM_INDEX = 145
RIGHT_EYE_TOP_INDEX = 386
RIGHT_EYE_BOTTOM_INDEX = 374

MOUTH_TOP_INDEX = 13
MOUTH_BOTTOM_INDEX = 14
MOUTH_LEFT_INDEX = 61
MOUTH_RIGHT_INDEX = 291

# MediaPipe Face Landmarker는 478개 랜드마크를 반환하며, 468~477번이 눈동자(Iris) 좌표입니다.
LEFT_IRIS_CENTER_INDEX = 468
RIGHT_IRIS_CENTER_INDEX = 473

# 눈동자가 눈 영역 중 어느 위치에 있으면 "카메라를 응시 중"으로 볼지 정하는 기준값입니다.
# 0.5가 눈 정중앙이며, 값이 좁을수록(0.5에 가까울수록) 정면 응시로 판단합니다.
GAZE_CAMERA_CENTER = 0.5
GAZE_CAMERA_CENTER_TOLERANCE = 0.15


def create_face_landmark_dict(landmark: Any) -> Dict[str, float]:
    return {
        "x": round(float(getattr(landmark, "x", 0)), 4),
        "y": round(float(getattr(landmark, "y", 0)), 4),
    }


def analyze_face_from_frames(
    sampled_frames: List[Dict[str, Any]],
    mp_images: List[mp.Image | None],
) -> Dict[str, Any]:
    if not sampled_frames:
        return create_empty_face_result()

    analyzed_frames: List[Dict[str, Any]] = []
    detected_count = 0
    gaze_scores: List[int] = []
    camera_gaze_frame_count = 0

    with model_registry.face_landmarker_context() as landmarker:
        for frame_index, frame_info in enumerate(sampled_frames):
            mp_image = mp_images[frame_index] if frame_index < len(mp_images) else None
            if mp_image is None:
                analyzed_frames.append(create_face_frame_result(frame_info, False))
                continue

            result = landmarker.detect(mp_image)

            if not result.face_landmarks:
                analyzed_frames.append(create_face_frame_result(frame_info, False))
                continue

            landmarks = result.face_landmarks[0]

            required_max_index = max(
                RIGHT_EYE_OUTER_INDEX,
                RIGHT_EYE_BOTTOM_INDEX,
                MOUTH_RIGHT_INDEX,
                RIGHT_IRIS_CENTER_INDEX,
            )

            if len(landmarks) <= required_max_index:
                analyzed_frames.append(create_face_frame_result(frame_info, False))
                continue

            detected_count += 1

            left_eye_outer = landmarks[LEFT_EYE_OUTER_INDEX]
            left_eye_inner = landmarks[LEFT_EYE_INNER_INDEX]
            right_eye_inner = landmarks[RIGHT_EYE_INNER_INDEX]
            right_eye_outer = landmarks[RIGHT_EYE_OUTER_INDEX]
            nose_tip = landmarks[NOSE_TIP_INDEX]

            left_eye_top = landmarks[LEFT_EYE_TOP_INDEX]
            left_eye_bottom = landmarks[LEFT_EYE_BOTTOM_INDEX]
            right_eye_top = landmarks[RIGHT_EYE_TOP_INDEX]
            right_eye_bottom = landmarks[RIGHT_EYE_BOTTOM_INDEX]

            mouth_top = landmarks[MOUTH_TOP_INDEX]
            mouth_bottom = landmarks[MOUTH_BOTTOM_INDEX]
            mouth_left = landmarks[MOUTH_LEFT_INDEX]
            mouth_right = landmarks[MOUTH_RIGHT_INDEX]

            left_iris_center = landmarks[LEFT_IRIS_CENTER_INDEX]
            right_iris_center = landmarks[RIGHT_IRIS_CENTER_INDEX]

            left_gaze_ratio = calculate_iris_gaze_ratio(
                iris_center=left_iris_center,
                eye_horizontal_a=left_eye_outer,
                eye_horizontal_b=left_eye_inner,
                eye_top=left_eye_top,
                eye_bottom=left_eye_bottom,
            )
            right_gaze_ratio = calculate_iris_gaze_ratio(
                iris_center=right_iris_center,
                eye_horizontal_a=right_eye_outer,
                eye_horizontal_b=right_eye_inner,
                eye_top=right_eye_top,
                eye_bottom=right_eye_bottom,
            )

            gaze_ratios = average_gaze_ratios(left_gaze_ratio, right_gaze_ratio)

            if gaze_ratios is None:
                # 측정 불가를 정면 응시로 오인하지 않고 시선 점수 집계에서 제외합니다.
                horizontal_ratio = None
                vertical_ratio = None
                gaze_score = None
                gazing_at_camera = False
            else:
                horizontal_ratio, vertical_ratio = gaze_ratios
                gaze_score = calculate_gaze_score_from_ratios(
                    horizontal_ratio,
                    vertical_ratio,
                )
                gazing_at_camera = is_gazing_at_camera(
                    horizontal_ratio,
                    vertical_ratio,
                )
                gaze_scores.append(gaze_score)

            if gazing_at_camera:
                camera_gaze_frame_count += 1

            mouth_openness = calculate_mouth_openness(
                mouth_top=mouth_top,
                mouth_bottom=mouth_bottom,
                mouth_left=mouth_left,
                mouth_right=mouth_right,
            )

            eye_openness = calculate_eye_openness(
                left_eye_top=left_eye_top,
                left_eye_bottom=left_eye_bottom,
                right_eye_top=right_eye_top,
                right_eye_bottom=right_eye_bottom,
                left_eye_outer=left_eye_outer,
                right_eye_outer=right_eye_outer,
            )

            analyzed_frames.append(
                {
                    "sequence": frame_info.get("sequence"),
                    "timestampSec": frame_info.get("timestampSec"),
                    "faceDetected": True,
                    "irisHorizontalRatio": horizontal_ratio,
                    "irisVerticalRatio": vertical_ratio,
                    "gazingAtCamera": gazing_at_camera,
                    "gazeScore": gaze_score,
                    "mouthOpenness": mouth_openness,
                    "eyeOpenness": eye_openness,
                    "landmarks": {
                        "leftEyeOuter": create_face_landmark_dict(left_eye_outer),
                        "rightEyeOuter": create_face_landmark_dict(right_eye_outer),
                        "noseTip": create_face_landmark_dict(nose_tip),
                    },
                }
            )

    total_frames = len(sampled_frames)
    detection_rate = round(detected_count / total_frames, 4) if total_frames > 0 else 0
    average_gaze_score = scoring.calculate_average_int(gaze_scores)
    camera_gaze_ratio = (
        round(camera_gaze_frame_count / detected_count, 4) if detected_count > 0 else 0
    )
    gaze_score = calculate_gaze_score_from_camera_ratio(camera_gaze_ratio)
    eye_contact_level = resolve_eye_contact_level(gaze_score)

    return {
        "analysisMethod": "mediapipe_tasks_face_landmarker_iris_gaze_ratio",
        "detectionRate": detection_rate,
        "detectedFrameCount": detected_count,
        "totalFrameCount": total_frames,
        "gazeScore": gaze_score,
        "averageFrameGazeScore": average_gaze_score,
        "cameraGazeRatio": camera_gaze_ratio,
        "eyeContactLevel": eye_contact_level,
        "frameResults": analyzed_frames,
    }


def calculate_mouth_openness(
    mouth_top: Any,
    mouth_bottom: Any,
    mouth_left: Any,
    mouth_right: Any,
) -> float:
    vertical_gap = abs(mouth_bottom.y - mouth_top.y)
    horizontal_gap = abs(mouth_right.x - mouth_left.x)

    if horizontal_gap <= 0:
        return 0

    return round(vertical_gap / horizontal_gap, 4)


def calculate_eye_openness(
    left_eye_top: Any,
    left_eye_bottom: Any,
    right_eye_top: Any,
    right_eye_bottom: Any,
    left_eye_outer: Any,
    right_eye_outer: Any,
) -> float:
    left_eye_gap = abs(left_eye_bottom.y - left_eye_top.y)
    right_eye_gap = abs(right_eye_bottom.y - right_eye_top.y)
    face_width = abs(right_eye_outer.x - left_eye_outer.x)

    if face_width <= 0:
        return 0

    average_eye_gap = (left_eye_gap + right_eye_gap) / 2
    return round(average_eye_gap / face_width, 4)


def create_face_frame_result(
    frame_info: Dict[str, Any],
    detected: bool,
) -> Dict[str, Any]:
    return {
        "sequence": frame_info.get("sequence"),
        "timestampSec": frame_info.get("timestampSec"),
        "faceDetected": detected,
        "irisHorizontalRatio": None,
        "irisVerticalRatio": None,
        "gazingAtCamera": False,
        "gazeScore": 0,
        "mouthOpenness": 0,
        "eyeOpenness": 0,
    }


def analyze_emotion_from_face_result(face_result: Dict[str, Any]) -> Dict[str, Any]:
    frame_results = face_result.get("frameResults", [])

    if not frame_results:
        return create_empty_emotion_result()

    emotion_frames: List[Dict[str, Any]] = []
    emotion_counts: Dict[str, int] = {
        "neutral": 0,
        "engaged": 0,
        "speaking": 0,
        "low_energy": 0,
        "unknown": 0,
    }
    expressiveness_scores: List[int] = []

    for frame_result in frame_results:
        if not frame_result.get("faceDetected"):
            emotion_label = "unknown"
            expression_score = 0
        else:
            mouth_openness = float(frame_result.get("mouthOpenness", 0))
            eye_openness = float(frame_result.get("eyeOpenness", 0))
            gaze_score = int(frame_result.get("gazeScore", 0))

            emotion_label = estimate_emotion_label(
                mouth_openness=mouth_openness,
                eye_openness=eye_openness,
                gaze_score=gaze_score,
            )
            expression_score = calculate_expression_score(
                emotion_label=emotion_label,
                mouth_openness=mouth_openness,
                eye_openness=eye_openness,
                gaze_score=gaze_score,
            )

        emotion_counts[emotion_label] = emotion_counts.get(emotion_label, 0) + 1
        expressiveness_scores.append(expression_score)
        emotion_frames.append(
            {
                "sequence": frame_result.get("sequence"),
                "timestampSec": frame_result.get("timestampSec"),
                "faceDetected": frame_result.get("faceDetected", False),
                "emotionLabel": emotion_label,
                "expressionScore": expression_score,
                "mouthOpenness": frame_result.get("mouthOpenness", 0),
                "eyeOpenness": frame_result.get("eyeOpenness", 0),
                "gazeScore": frame_result.get("gazeScore", 0),
            }
        )

    total_frames = len(frame_results)
    detected_frame_count = sum(
        1 for frame in frame_results if frame.get("faceDetected")
    )
    detection_rate = (
        round(detected_frame_count / total_frames, 4) if total_frames > 0 else 0
    )
    expression_raw_score = scoring.calculate_average_int(expressiveness_scores)
    expression_variety_score = calculate_expression_variety_score(emotion_counts)
    expression_score = int(
        expression_raw_score * 0.65
        + expression_variety_score * 0.20
        + int(detection_rate * 100) * 0.15
    )
    expression_score = max(0, min(expression_score, 100))
    dominant_emotion = resolve_dominant_emotion(emotion_counts)

    return {
        "analysisMethod": "mediapipe_tasks_face_landmarker_expression_based",
        "expressionScore": expression_score,
        "expressionRawScore": expression_raw_score,
        "expressionVarietyScore": expression_variety_score,
        "emotionState": {
            "dominantEmotion": dominant_emotion,
            "emotionCounts": emotion_counts,
            "note": "감정 상태 분류는 표정 점수의 보조 지표이며 총점에는 반영하지 않습니다.",
        },
        "detectionRate": detection_rate,
        "detectedFrameCount": detected_frame_count,
        "totalFrameCount": total_frames,
        "frameResults": emotion_frames,
        "note": "MediaPipe Tasks FaceLandmarker의 입 벌림, 눈 뜸 정도, 시선 안정성을 기반으로 발표 표정 상태를 추정했습니다.",
    }


def estimate_emotion_label(
    mouth_openness: float,
    eye_openness: float,
    gaze_score: int,
) -> str:
    if mouth_openness >= 0.28 and eye_openness >= 0.035:
        return "speaking"

    if gaze_score >= 80 and eye_openness >= 0.03:
        return "engaged"

    if eye_openness < 0.018:
        return "low_energy"

    return "neutral"


def calculate_expression_score(
    emotion_label: str,
    mouth_openness: float,
    eye_openness: float,
    gaze_score: int,
) -> int:
    if emotion_label == "engaged":
        return min(100, int(gaze_score * 0.75 + 25))

    if emotion_label == "speaking":
        mouth_score = min(100, int(mouth_openness * 260))
        eye_score = min(100, int(eye_openness * 1800))
        return int(mouth_score * 0.6 + eye_score * 0.4)

    if emotion_label == "neutral":
        return 70

    if emotion_label == "low_energy":
        return 45

    return 0


def calculate_expression_variety_score(emotion_counts: Dict[str, int]) -> int:
    active_emotions = [
        emotion
        for emotion, count in emotion_counts.items()
        if count > 0 and emotion != "unknown"
    ]
    count = len(active_emotions)

    if count >= 3:
        return 100
    if count == 2:
        return 80
    if count == 1:
        return 60
    return 40


def resolve_dominant_emotion(emotion_counts: Dict[str, int]) -> str:
    filtered_counts = {
        emotion: count
        for emotion, count in emotion_counts.items()
        if emotion != "unknown"
    }

    if not any(filtered_counts.values()):
        return "unknown"

    return max(filtered_counts, key=filtered_counts.get)


def calculate_iris_gaze_ratio(
    iris_center: Any,
    eye_horizontal_a: Any,
    eye_horizontal_b: Any,
    eye_top: Any,
    eye_bottom: Any,
) -> Dict[str, float] | None:
    horizontal_span = abs(eye_horizontal_a.x - eye_horizontal_b.x)
    vertical_span = abs(eye_bottom.y - eye_top.y)

    if horizontal_span <= 0 or vertical_span <= 0:
        return None

    left_bound_x = min(eye_horizontal_a.x, eye_horizontal_b.x)
    top_bound_y = min(eye_top.y, eye_bottom.y)

    return {
        "horizontalRatio": (iris_center.x - left_bound_x) / horizontal_span,
        "verticalRatio": (iris_center.y - top_bound_y) / vertical_span,
    }


def average_gaze_ratios(
    left_gaze_ratio: Dict[str, float] | None,
    right_gaze_ratio: Dict[str, float] | None,
) -> tuple[float, float] | None:
    ratios = [
        ratio for ratio in (left_gaze_ratio, right_gaze_ratio) if ratio is not None
    ]

    if not ratios:
        return None

    horizontal_ratio = sum(ratio["horizontalRatio"] for ratio in ratios) / len(ratios)
    vertical_ratio = sum(ratio["verticalRatio"] for ratio in ratios) / len(ratios)
    return round(horizontal_ratio, 4), round(vertical_ratio, 4)


def is_gazing_at_camera(horizontal_ratio: float, vertical_ratio: float) -> bool:
    horizontal_offset = abs(horizontal_ratio - GAZE_CAMERA_CENTER)
    vertical_offset = abs(vertical_ratio - GAZE_CAMERA_CENTER)
    return (
        horizontal_offset <= GAZE_CAMERA_CENTER_TOLERANCE
        and vertical_offset <= GAZE_CAMERA_CENTER_TOLERANCE
    )


def calculate_gaze_score_from_ratios(
    horizontal_ratio: float,
    vertical_ratio: float,
) -> int:
    offset = max(
        abs(horizontal_ratio - GAZE_CAMERA_CENTER),
        abs(vertical_ratio - GAZE_CAMERA_CENTER),
    )

    if offset < 0.12:
        return 100
    if offset < 0.20:
        return 80
    if offset < 0.30:
        return 60
    return 40


def calculate_gaze_score_from_camera_ratio(camera_gaze_ratio: float) -> int:
    if camera_gaze_ratio >= 0.70:
        return 90
    if camera_gaze_ratio >= 0.40:
        return int(60 + (camera_gaze_ratio - 0.40) / 0.30 * 29)
    return int(camera_gaze_ratio / 0.40 * 59)


def resolve_eye_contact_level(gaze_score: int) -> str:
    if gaze_score >= 85:
        return "good"
    if gaze_score >= 70:
        return "normal"
    if gaze_score >= 50:
        return "weak"
    return "poor"


def create_empty_face_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "mediapipe_tasks_face_landmarker_iris_gaze_ratio",
        "detectionRate": 0,
        "detectedFrameCount": 0,
        "totalFrameCount": 0,
        "gazeScore": 0,
        "averageFrameGazeScore": 0,
        "cameraGazeRatio": 0,
        "eyeContactLevel": "unknown",
        "frameResults": [],
    }


def create_empty_emotion_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "mediapipe_tasks_face_landmarker_expression_based",
        "expressionScore": 0,
        "expressionRawScore": 0,
        "expressionVarietyScore": 0,
        "emotionState": {
            "dominantEmotion": "unknown",
            "emotionCounts": {
                "neutral": 0,
                "engaged": 0,
                "speaking": 0,
                "low_energy": 0,
                "unknown": 0,
            },
            "note": "감정 상태 분류는 표정 점수의 보조 지표이며 총점에는 반영하지 않습니다.",
        },
        "detectionRate": 0,
        "detectedFrameCount": 0,
        "totalFrameCount": 0,
        "frameResults": [],
        "note": "얼굴 분석 결과가 없어 표정 분석을 수행하지 못했습니다.",
    }
