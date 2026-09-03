"""샘플 프레임 위에 MediaPipe 포즈/제스처 분석 결과를 그려 base64 JPEG로 인코딩합니다.

사용자에게 "영상의 이 장면을 이렇게 봤다"를 보여주기 위한 시각화입니다. 어깨·팔꿈치·
손목 랜드마크와 그 연결선, 어깨 균형선, 제스처 활성 여부만 표시합니다(시선·표정은
현재 점수 산식에서 제외되어 여기서도 그리지 않습니다).

결과는 분석 응답의 ``frameOverlays`` 로 백엔드에 전달되고, 백엔드가 이미지를 스토리지에
저장한 뒤 원본 응답에서는 제거합니다. 렌더 실패는 개별 프레임을 건너뛰는 것으로 처리하고
분석 파이프라인을 막지 않습니다.
"""

import base64
import logging
from typing import Any, Dict, List, Optional, Tuple

import cv2

logger = logging.getLogger("analysis-engine")

OVERLAY_MAX_WIDTH = 640
OVERLAY_JPEG_QUALITY = 75
MIN_LANDMARK_VISIBILITY = 0.3

_JOINT_KEYS = (
    "leftShoulder",
    "rightShoulder",
    "leftElbow",
    "rightElbow",
    "leftWrist",
    "rightWrist",
)

_SKELETON_EDGES = (
    ("leftShoulder", "rightShoulder"),
    ("leftShoulder", "leftElbow"),
    ("leftElbow", "leftWrist"),
    ("rightShoulder", "rightElbow"),
    ("rightElbow", "rightWrist"),
)

# OpenCV는 BGR 순서입니다.
_COLOR_EDGE = (255, 200, 0)
_COLOR_JOINT = (0, 255, 0)
_COLOR_BALANCED = (0, 200, 0)
_COLOR_UNBALANCED = (0, 165, 255)
_COLOR_WARN = (0, 0, 255)


def build_frame_overlays(
    sampled_frames: List[Dict[str, Any]],
    pose_frame_results: List[Dict[str, Any]],
    gesture_frame_results: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    pose_by_sequence = {
        result.get("sequence"): result for result in pose_frame_results
    }
    gesture_by_sequence = {
        result.get("sequence"): result for result in gesture_frame_results
    }

    overlays: List[Dict[str, Any]] = []

    for frame_info in sampled_frames:
        sequence = frame_info.get("sequence")
        frame_path = frame_info.get("framePath")
        image = cv2.imread(frame_path) if frame_path else None

        if image is None:
            logger.debug("오버레이 생성을 건너뜁니다(프레임 로드 실패): %s", frame_path)
            continue

        pose_result = pose_by_sequence.get(sequence)
        gesture_result = gesture_by_sequence.get(sequence)

        try:
            annotated_image = _draw_overlay(image, pose_result, gesture_result)
            encoded_image = _encode_jpeg_base64(annotated_image)
        except Exception as exception:  # noqa: BLE001 - 개별 프레임 실패는 건너뜁니다.
            logger.debug("오버레이 렌더 실패(sequence=%s): %s", sequence, exception)
            continue

        if encoded_image is None:
            continue

        overlays.append(
            {
                "sequence": sequence,
                "timestampSec": frame_info.get("timestampSec"),
                "poseDetected": bool(
                    pose_result and pose_result.get("poseDetected")
                ),
                "gestureDetected": bool(
                    gesture_result and gesture_result.get("gestureDetected")
                ),
                "imageMimeType": "image/jpeg",
                "imageBase64": encoded_image,
            }
        )

    return overlays


def _draw_overlay(
    image: Any,
    pose_result: Optional[Dict[str, Any]],
    gesture_result: Optional[Dict[str, Any]],
) -> Any:
    image = _resize_for_overlay(image)
    height, width = image.shape[:2]

    if not pose_result or not pose_result.get("poseDetected"):
        _draw_label(image, "no pose detected", _COLOR_WARN)
        return image

    def to_point(joint_key: str) -> Optional[Tuple[int, int]]:
        landmark = pose_result.get(joint_key)
        if not isinstance(landmark, dict):
            return None

        visibility = landmark.get("visibility", 1)
        if visibility is not None and visibility < MIN_LANDMARK_VISIBILITY:
            return None

        return (
            int(float(landmark.get("x", 0)) * width),
            int(float(landmark.get("y", 0)) * height),
        )

    for start_key, end_key in _SKELETON_EDGES:
        start_point = to_point(start_key)
        end_point = to_point(end_key)
        if start_point and end_point:
            cv2.line(image, start_point, end_point, _COLOR_EDGE, 2, cv2.LINE_AA)

    for joint_key in _JOINT_KEYS:
        point = to_point(joint_key)
        if point:
            cv2.circle(image, point, 4, _COLOR_JOINT, -1, cv2.LINE_AA)

    left_shoulder = to_point("leftShoulder")
    right_shoulder = to_point("rightShoulder")
    if left_shoulder and right_shoulder:
        balanced = abs(left_shoulder[1] - right_shoulder[1]) < height * 0.03
        shoulder_color = _COLOR_BALANCED if balanced else _COLOR_UNBALANCED
        cv2.line(image, left_shoulder, right_shoulder, shoulder_color, 2, cv2.LINE_AA)

    if gesture_result and gesture_result.get("gestureDetected"):
        _draw_label(image, "gesture active", _COLOR_JOINT)

    return image


def _resize_for_overlay(image: Any) -> Any:
    height, width = image.shape[:2]
    if width <= OVERLAY_MAX_WIDTH or width <= 0:
        return image

    scale = OVERLAY_MAX_WIDTH / width
    return cv2.resize(
        image,
        (OVERLAY_MAX_WIDTH, max(1, int(height * scale))),
        interpolation=cv2.INTER_AREA,
    )


def _draw_label(image: Any, text: str, color: Tuple[int, int, int]) -> None:
    cv2.putText(
        image,
        text,
        (10, 24),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        color,
        2,
        cv2.LINE_AA,
    )


def _encode_jpeg_base64(image: Any) -> Optional[str]:
    success, buffer = cv2.imencode(
        ".jpg",
        image,
        [int(cv2.IMWRITE_JPEG_QUALITY), OVERLAY_JPEG_QUALITY],
    )
    if not success:
        return None

    return base64.b64encode(buffer.tobytes()).decode("ascii")
