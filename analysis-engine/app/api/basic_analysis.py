from pathlib import Path
from typing import Any, Dict, List

import cv2
import mediapipe as mp
from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(prefix="/api", tags=["basic-analysis"])

MAX_EXTRACTED_FRAMES = 20
FRAME_EXTRACT_INTERVAL_SEC = 1

LEFT_SHOULDER_INDEX = 11
RIGHT_SHOULDER_INDEX = 12

LEFT_EYE_OUTER_INDEX = 33
LEFT_EYE_INNER_INDEX = 133
RIGHT_EYE_INNER_INDEX = 362
RIGHT_EYE_OUTER_INDEX = 263
NOSE_TIP_INDEX = 1


class BasicAnalysisRequest(BaseModel):
    jobId: str
    videoPath: str


@router.post("/basic-analysis")
def basic_analysis(request: BasicAnalysisRequest) -> Dict[str, Any]:
    resolved_video_path = resolve_video_path(request.videoPath)

    if resolved_video_path is None:
        return create_failed_response(
            job_id=request.jobId,
            video_path=request.videoPath,
            reason="영상 파일을 찾을 수 없습니다.",
        )

    video_info = extract_video_info(resolved_video_path)

    if video_info["readable"] is False:
        return create_failed_response(
            job_id=request.jobId,
            video_path=str(resolved_video_path),
            reason="영상 파일을 읽을 수 없습니다.",
        )

    frame_result = extract_sample_frames(
        job_id=request.jobId,
        video_path=resolved_video_path,
        fps=video_info["fps"],
        frame_count=video_info["frameCount"],
    )

    pose_result = analyze_pose_from_frames(frame_result["sampledFrames"])
    face_result = analyze_face_from_frames(frame_result["sampledFrames"])
    audio_result = analyze_speech_from_video_duration(video_info["durationSec"])
    filler_result = analyze_filler_from_speech(audio_result)

    score_result = calculate_score(
        pose_result=pose_result,
        face_result=face_result,
        audio_result=audio_result,
    )

    return {
        "jobId": request.jobId,
        "status": "success",
        "videoInfo": {
            "videoPath": str(resolved_video_path),
            "durationSec": video_info["durationSec"],
            "fps": video_info["fps"],
            "frameCount": video_info["frameCount"],
            "width": video_info["width"],
            "height": video_info["height"],
            "fileSize": video_info["fileSize"],
        },
        "frame": {
            "savedCount": frame_result["savedCount"],
            "frameDirectory": frame_result["frameDirectory"],
            "sampledFrames": frame_result["sampledFrames"],
        },
        "audio": audio_result,
        "filler": filler_result,
        "pose": pose_result,
        "face": face_result,
        "score": score_result,
    }


def resolve_video_path(video_path: str) -> Path | None:
    input_path = Path(video_path)

    candidate_paths = [
        input_path,
        Path.cwd() / input_path,
        Path.cwd().parent / input_path,
        ]

    if input_path.is_absolute():
        candidate_paths.insert(0, input_path)

    for candidate_path in candidate_paths:
        normalized_path = candidate_path.resolve()

        if normalized_path.exists() and normalized_path.is_file():
            return normalized_path

    return None


def resolve_project_root() -> Path:
    current_path = Path.cwd().resolve()

    if current_path.name == "analysis-engine":
        return current_path.parent

    if (current_path / "storage").exists():
        return current_path

    if (current_path.parent / "storage").exists():
        return current_path.parent

    return current_path.parent


def extract_video_info(video_path: Path) -> Dict[str, Any]:
    file_size = video_path.stat().st_size

    capture = cv2.VideoCapture(str(video_path))

    if not capture.isOpened():
        return {
            "readable": False,
            "durationSec": 0,
            "fps": 0,
            "frameCount": 0,
            "width": 0,
            "height": 0,
            "fileSize": file_size,
        }

    fps = capture.get(cv2.CAP_PROP_FPS)
    frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))

    capture.release()

    duration_sec = 0

    if fps and fps > 0:
        duration_sec = round(frame_count / fps, 2)

    return {
        "readable": True,
        "durationSec": duration_sec,
        "fps": round(fps, 2) if fps else 0,
        "frameCount": frame_count,
        "width": width,
        "height": height,
        "fileSize": file_size,
    }


def extract_sample_frames(
        job_id: str,
        video_path: Path,
        fps: float,
        frame_count: int,
) -> Dict[str, Any]:
    project_root = resolve_project_root()
    frame_directory = project_root / "storage" / "temp" / job_id / "frames"
    frame_directory.mkdir(parents=True, exist_ok=True)

    capture = cv2.VideoCapture(str(video_path))

    if not capture.isOpened():
        return {
            "savedCount": 0,
            "frameDirectory": str(frame_directory),
            "sampledFrames": [],
        }

    frame_indexes = calculate_sample_frame_indexes(
        fps=fps,
        frame_count=frame_count,
    )

    sampled_frames: List[Dict[str, Any]] = []

    for sequence, frame_index in enumerate(frame_indexes, start=1):
        capture.set(cv2.CAP_PROP_POS_FRAMES, frame_index)

        success, frame = capture.read()

        if not success:
            continue

        timestamp_sec = round(frame_index / fps, 2) if fps > 0 else 0
        frame_file_name = f"frame_{sequence:03d}_{timestamp_sec:.2f}s.jpg"
        frame_path = frame_directory / frame_file_name

        saved = cv2.imwrite(str(frame_path), frame)

        if not saved:
            continue

        sampled_frames.append(
            {
                "sequence": sequence,
                "frameIndex": frame_index,
                "timestampSec": timestamp_sec,
                "framePath": str(frame_path),
            }
        )

    capture.release()

    return {
        "savedCount": len(sampled_frames),
        "frameDirectory": str(frame_directory),
        "sampledFrames": sampled_frames,
    }


def calculate_sample_frame_indexes(
        fps: float,
        frame_count: int,
) -> List[int]:
    if fps <= 0 or frame_count <= 0:
        return []

    interval_frame_count = max(int(fps * FRAME_EXTRACT_INTERVAL_SEC), 1)

    frame_indexes = list(range(0, frame_count, interval_frame_count))

    if len(frame_indexes) > MAX_EXTRACTED_FRAMES:
        step = len(frame_indexes) / MAX_EXTRACTED_FRAMES
        frame_indexes = [
            frame_indexes[int(index * step)]
            for index in range(MAX_EXTRACTED_FRAMES)
        ]

    return sorted(set(frame_indexes))


def analyze_pose_from_frames(
        sampled_frames: List[Dict[str, Any]]
) -> Dict[str, Any]:
    if not sampled_frames:
        return create_empty_pose_result()

    mp_pose = mp.solutions.pose

    analyzed_frames: List[Dict[str, Any]] = []
    detected_count = 0
    shoulder_balance_scores: List[int] = []
    shoulder_diffs: List[float] = []

    with mp_pose.Pose(
            static_image_mode=True,
            model_complexity=1,
            enable_segmentation=False,
            min_detection_confidence=0.5,
    ) as pose:
        for frame_info in sampled_frames:
            frame_path = frame_info.get("framePath")

            if not frame_path:
                continue

            image = cv2.imread(frame_path)

            if image is None:
                analyzed_frames.append(create_pose_frame_result(frame_info, False))
                continue

            rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            result = pose.process(rgb_image)

            if not result.pose_landmarks:
                analyzed_frames.append(create_pose_frame_result(frame_info, False))
                continue

            detected_count += 1

            landmarks = result.pose_landmarks.landmark

            left_shoulder = landmarks[LEFT_SHOULDER_INDEX]
            right_shoulder = landmarks[RIGHT_SHOULDER_INDEX]

            shoulder_diff = abs(left_shoulder.y - right_shoulder.y)
            shoulder_score = calculate_shoulder_balance_score(shoulder_diff)

            shoulder_diffs.append(shoulder_diff)
            shoulder_balance_scores.append(shoulder_score)

            analyzed_frames.append(
                {
                    "sequence": frame_info.get("sequence"),
                    "timestampSec": frame_info.get("timestampSec"),
                    "poseDetected": True,
                    "leftShoulder": {
                        "x": round(left_shoulder.x, 4),
                        "y": round(left_shoulder.y, 4),
                        "visibility": round(left_shoulder.visibility, 4),
                    },
                    "rightShoulder": {
                        "x": round(right_shoulder.x, 4),
                        "y": round(right_shoulder.y, 4),
                        "visibility": round(right_shoulder.visibility, 4),
                    },
                    "shoulderDiff": round(shoulder_diff, 4),
                    "shoulderBalanceScore": shoulder_score,
                }
            )

    total_frames = len(sampled_frames)
    detection_rate = round(detected_count / total_frames, 4) if total_frames > 0 else 0

    average_shoulder_score = calculate_average_int(shoulder_balance_scores)
    average_shoulder_diff = calculate_average_float(shoulder_diffs)

    posture_score = calculate_posture_score(
        detection_rate=detection_rate,
        shoulder_balance_score=average_shoulder_score,
    )

    return {
        "detectionRate": detection_rate,
        "detectedFrameCount": detected_count,
        "totalFrameCount": total_frames,
        "postureScore": posture_score,
        "shoulderBalanceScore": average_shoulder_score,
        "averageShoulderDiff": average_shoulder_diff,
        "frameResults": analyzed_frames,
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


def analyze_face_from_frames(
        sampled_frames: List[Dict[str, Any]]
) -> Dict[str, Any]:
    if not sampled_frames:
        return create_empty_face_result()

    mp_face_mesh = mp.solutions.face_mesh

    analyzed_frames: List[Dict[str, Any]] = []
    detected_count = 0
    gaze_scores: List[int] = []
    nose_offsets: List[float] = []

    with mp_face_mesh.FaceMesh(
            static_image_mode=True,
            max_num_faces=1,
            refine_landmarks=True,
            min_detection_confidence=0.5,
    ) as face_mesh:
        for frame_info in sampled_frames:
            frame_path = frame_info.get("framePath")

            if not frame_path:
                continue

            image = cv2.imread(frame_path)

            if image is None:
                analyzed_frames.append(create_face_frame_result(frame_info, False))
                continue

            rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            result = face_mesh.process(rgb_image)

            if not result.multi_face_landmarks:
                analyzed_frames.append(create_face_frame_result(frame_info, False))
                continue

            detected_count += 1

            landmarks = result.multi_face_landmarks[0].landmark

            left_eye_outer = landmarks[LEFT_EYE_OUTER_INDEX]
            left_eye_inner = landmarks[LEFT_EYE_INNER_INDEX]
            right_eye_inner = landmarks[RIGHT_EYE_INNER_INDEX]
            right_eye_outer = landmarks[RIGHT_EYE_OUTER_INDEX]
            nose_tip = landmarks[NOSE_TIP_INDEX]

            left_eye_center_x = (left_eye_outer.x + left_eye_inner.x) / 2
            right_eye_center_x = (right_eye_outer.x + right_eye_inner.x) / 2
            eye_center_x = (left_eye_center_x + right_eye_center_x) / 2

            nose_offset = nose_tip.x - eye_center_x
            abs_nose_offset = abs(nose_offset)

            gaze_score = calculate_gaze_score(abs_nose_offset)
            gaze_direction = estimate_gaze_direction(nose_offset)

            gaze_scores.append(gaze_score)
            nose_offsets.append(abs_nose_offset)

            analyzed_frames.append(
                {
                    "sequence": frame_info.get("sequence"),
                    "timestampSec": frame_info.get("timestampSec"),
                    "faceDetected": True,
                    "noseOffset": round(nose_offset, 4),
                    "absNoseOffset": round(abs_nose_offset, 4),
                    "gazeDirection": gaze_direction,
                    "gazeScore": gaze_score,
                    "landmarks": {
                        "leftEyeOuter": {
                            "x": round(left_eye_outer.x, 4),
                            "y": round(left_eye_outer.y, 4),
                        },
                        "rightEyeOuter": {
                            "x": round(right_eye_outer.x, 4),
                            "y": round(right_eye_outer.y, 4),
                        },
                        "noseTip": {
                            "x": round(nose_tip.x, 4),
                            "y": round(nose_tip.y, 4),
                        },
                    },
                }
            )

    total_frames = len(sampled_frames)
    detection_rate = round(detected_count / total_frames, 4) if total_frames > 0 else 0
    average_gaze_score = calculate_average_int(gaze_scores)
    average_nose_offset = calculate_average_float(nose_offsets)
    eye_contact_level = resolve_eye_contact_level(average_gaze_score)

    return {
        "detectionRate": detection_rate,
        "detectedFrameCount": detected_count,
        "totalFrameCount": total_frames,
        "gazeScore": average_gaze_score,
        "averageNoseOffset": average_nose_offset,
        "eyeContactLevel": eye_contact_level,
        "frameResults": analyzed_frames,
    }


def create_face_frame_result(
        frame_info: Dict[str, Any],
        detected: bool,
) -> Dict[str, Any]:
    return {
        "sequence": frame_info.get("sequence"),
        "timestampSec": frame_info.get("timestampSec"),
        "faceDetected": detected,
        "noseOffset": None,
        "absNoseOffset": None,
        "gazeDirection": "unknown",
        "gazeScore": 0,
    }


def analyze_speech_from_video_duration(duration_sec: float) -> Dict[str, Any]:
    safe_duration_sec = max(duration_sec, 0)
    estimated_speech_duration_sec = round(safe_duration_sec * 0.82, 2)
    estimated_pause_duration_sec = round(safe_duration_sec - estimated_speech_duration_sec, 2)

    estimated_word_count = estimate_word_count(estimated_speech_duration_sec)
    speech_speed_wpm = calculate_speech_speed_wpm(
        word_count=estimated_word_count,
        speech_duration_sec=estimated_speech_duration_sec,
    )

    silence_count = estimate_silence_count(safe_duration_sec)
    silence_ratio = calculate_silence_ratio(
        silence_duration_sec=estimated_pause_duration_sec,
        duration_sec=safe_duration_sec,
    )

    speech_speed_score = calculate_speech_speed_score(speech_speed_wpm)
    silence_score = calculate_silence_score(silence_ratio)

    speech_score = int(
        speech_speed_score * 0.7
        + silence_score * 0.3
    )

    return {
        "analysisMethod": "duration_based_estimation",
        "durationSec": safe_duration_sec,
        "estimatedSpeechDurationSec": estimated_speech_duration_sec,
        "estimatedPauseDurationSec": estimated_pause_duration_sec,
        "estimatedWordCount": estimated_word_count,
        "speechSpeedWpm": speech_speed_wpm,
        "speechSpeedScore": speech_speed_score,
        "silenceCount": silence_count,
        "totalSilenceTime": estimated_pause_duration_sec,
        "silenceRatio": silence_ratio,
        "silenceScore": silence_score,
        "speechScore": speech_score,
        "note": "현재 단계는 실제 음성 인식이 아닌 영상 길이 기반 추정 분석입니다.",
    }


def estimate_word_count(speech_duration_sec: float) -> int:
    if speech_duration_sec <= 0:
        return 0

    baseline_wpm = 130
    return int((speech_duration_sec / 60) * baseline_wpm)


def calculate_speech_speed_wpm(
        word_count: int,
        speech_duration_sec: float,
) -> int:
    if speech_duration_sec <= 0:
        return 0

    return int(word_count / (speech_duration_sec / 60))


def estimate_silence_count(duration_sec: float) -> int:
    if duration_sec <= 0:
        return 0

    return max(1, int(duration_sec // 20))


def calculate_silence_ratio(
        silence_duration_sec: float,
        duration_sec: float,
) -> float:
    if duration_sec <= 0:
        return 0

    return round(silence_duration_sec / duration_sec, 4)


def calculate_speech_speed_score(speech_speed_wpm: int) -> int:
    if 110 <= speech_speed_wpm <= 150:
        return 100

    if 90 <= speech_speed_wpm < 110:
        return 80

    if 150 < speech_speed_wpm <= 170:
        return 80

    if 70 <= speech_speed_wpm < 90:
        return 60

    if 170 < speech_speed_wpm <= 190:
        return 60

    return 40


def calculate_silence_score(silence_ratio: float) -> int:
    if silence_ratio <= 0.15:
        return 100

    if silence_ratio <= 0.25:
        return 80

    if silence_ratio <= 0.35:
        return 60

    return 40


def analyze_filler_from_speech(audio_result: Dict[str, Any]) -> Dict[str, Any]:
    estimated_word_count = int(audio_result.get("estimatedWordCount", 0))
    duration_sec = float(audio_result.get("durationSec", 0))

    filler_count = estimate_filler_count(
        estimated_word_count=estimated_word_count,
        duration_sec=duration_sec,
    )

    filler_ratio = calculate_filler_ratio(
        filler_count=filler_count,
        estimated_word_count=estimated_word_count,
    )

    filler_score = calculate_filler_score(filler_ratio)

    return {
        "analysisMethod": "duration_based_estimation",
        "fillerCount": filler_count,
        "fillerRatio": filler_ratio,
        "fillerScore": filler_score,
        "note": "현재 단계는 실제 STT 기반 필러 검출이 아닌 추정값입니다.",
    }


def estimate_filler_count(
        estimated_word_count: int,
        duration_sec: float,
) -> int:
    if estimated_word_count <= 0 or duration_sec <= 0:
        return 0

    return max(0, int(estimated_word_count * 0.025))


def calculate_filler_ratio(
        filler_count: int,
        estimated_word_count: int,
) -> float:
    if estimated_word_count <= 0:
        return 0

    return round(filler_count / estimated_word_count, 4)


def calculate_filler_score(filler_ratio: float) -> int:
    if filler_ratio <= 0.01:
        return 100

    if filler_ratio <= 0.03:
        return 80

    if filler_ratio <= 0.06:
        return 60

    return 40


def calculate_gaze_score(abs_nose_offset: float) -> int:
    if abs_nose_offset < 0.015:
        return 100

    if abs_nose_offset < 0.035:
        return 80

    if abs_nose_offset < 0.06:
        return 60

    return 40


def estimate_gaze_direction(nose_offset: float) -> str:
    if nose_offset < -0.035:
        return "left"

    if nose_offset > 0.035:
        return "right"

    return "center"


def resolve_eye_contact_level(gaze_score: int) -> str:
    if gaze_score >= 85:
        return "good"

    if gaze_score >= 70:
        return "normal"

    if gaze_score >= 50:
        return "weak"

    return "poor"


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

    posture_score = int(
        detection_score * 0.4
        + shoulder_balance_score * 0.6
    )

    return max(0, min(posture_score, 100))


def calculate_score(
        pose_result: Dict[str, Any],
        face_result: Dict[str, Any],
        audio_result: Dict[str, Any],
) -> Dict[str, Any]:
    posture_score = int(pose_result.get("postureScore", 0))
    gaze_score = int(face_result.get("gazeScore", 0))
    speech_score = int(audio_result.get("speechScore", 0))

    total_score = int(
        posture_score * 0.35
        + gaze_score * 0.30
        + speech_score * 0.35
    )

    return {
        "totalScore": total_score,
        "postureScore": posture_score,
        "gazeScore": gaze_score,
        "speechScore": speech_score,
    }


def calculate_average_int(values: List[int]) -> int:
    if not values:
        return 0

    return int(sum(values) / len(values))


def calculate_average_float(values: List[float]) -> float:
    if not values:
        return 0

    return round(sum(values) / len(values), 4)


def create_empty_pose_result() -> Dict[str, Any]:
    return {
        "detectionRate": 0,
        "detectedFrameCount": 0,
        "totalFrameCount": 0,
        "postureScore": 0,
        "shoulderBalanceScore": 0,
        "averageShoulderDiff": 0,
        "frameResults": [],
    }


def create_empty_face_result() -> Dict[str, Any]:
    return {
        "detectionRate": 0,
        "detectedFrameCount": 0,
        "totalFrameCount": 0,
        "gazeScore": 0,
        "averageNoseOffset": 0,
        "eyeContactLevel": "unknown",
        "frameResults": [],
    }


def create_empty_audio_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "duration_based_estimation",
        "durationSec": 0,
        "estimatedSpeechDurationSec": 0,
        "estimatedPauseDurationSec": 0,
        "estimatedWordCount": 0,
        "speechSpeedWpm": 0,
        "speechSpeedScore": 0,
        "silenceCount": 0,
        "totalSilenceTime": 0,
        "silenceRatio": 0,
        "silenceScore": 0,
        "speechScore": 0,
        "note": "영상 정보를 읽지 못해 음성 분석을 수행하지 못했습니다.",
    }


def create_empty_filler_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "duration_based_estimation",
        "fillerCount": 0,
        "fillerRatio": 0,
        "fillerScore": 0,
        "note": "영상 정보를 읽지 못해 필러 분석을 수행하지 못했습니다.",
    }


def create_failed_response(
        job_id: str,
        video_path: str,
        reason: str,
) -> Dict[str, Any]:
    return {
        "jobId": job_id,
        "status": "failed",
        "videoInfo": {
            "videoPath": video_path,
            "durationSec": 0,
            "fps": 0,
            "frameCount": 0,
            "width": 0,
            "height": 0,
            "fileSize": 0,
        },
        "frame": {
            "savedCount": 0,
            "frameDirectory": "",
            "sampledFrames": [],
        },
        "audio": create_empty_audio_result(),
        "filler": create_empty_filler_result(),
        "pose": create_empty_pose_result(),
        "face": create_empty_face_result(),
        "score": {
            "totalScore": 0,
            "postureScore": 0,
            "gazeScore": 0,
            "speechScore": 0,
        },
        "error": {
            "reason": reason,
        },
    }