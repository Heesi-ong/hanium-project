import logging
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, List

import cv2
import imageio_ffmpeg
import mediapipe as mp
import requests
from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel

from app.core import model_registry
from app.core.logging_config import bind_job_id, bind_request_id
from app.core.security import verify_internal_api_key

logger = logging.getLogger("analysis-engine")

router = APIRouter(
    prefix="/api",
    tags=["basic-analysis"],
    dependencies=[Depends(verify_internal_api_key)],
)

MAX_EXTRACTED_FRAMES = 20
FRAME_EXTRACT_INTERVAL_SEC = 1

LEFT_SHOULDER_INDEX = 11
RIGHT_SHOULDER_INDEX = 12
LEFT_ELBOW_INDEX = 13
RIGHT_ELBOW_INDEX = 14
LEFT_WRIST_INDEX = 15
RIGHT_WRIST_INDEX = 16

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

WHISPER_MODEL_SIZE = "base"
WHISPER_DEVICE = "cpu"
WHISPER_COMPUTE_TYPE = "int8"

# 음성 점수 내부 가중치입니다. '발표_코칭_점수화_알고리즘_선정_자료'의 권장 기준을 따릅니다.
# 말속도 35% + 침묵 25% + 필러 25% + 음량 안정성 15%.
SPEECH_SPEED_WEIGHT = 0.35
SILENCE_WEIGHT = 0.25
FILLER_WEIGHT = 0.25
VOLUME_STABILITY_WEIGHT = 0.15

# 음량(RMS/dB) 분석은 아직 구현되지 않았습니다. 구현 전까지 15% 자리를 흔들지 않도록
# 중립 기본값(80점)을 사용합니다. 실제 음량 분석이 들어오면 이 상수를 계산값으로 교체합니다.
VOLUME_STABILITY_BASELINE_SCORE = 80
VOLUME_STABILITY_IMPLEMENTED = False

# 전체 패널티(신뢰도) 기준입니다. 탐지율이 낮거나 영상이 너무 짧으면 총점에서 감점합니다.
POSE_LOW_DETECTION_THRESHOLD = 0.5
POSE_WEAK_DETECTION_THRESHOLD = 0.7
FACE_LOW_DETECTION_THRESHOLD = 0.5
FACE_WEAK_DETECTION_THRESHOLD = 0.7
SHORT_VIDEO_THRESHOLD_SEC = 10
MAX_TOTAL_PENALTY = 15

KOREAN_FILLER_WORDS = [
    "음",
    "어",
    "아",
    "그",
    "저",
    "음...",
    "어...",
    "아...",
    "그...",
    "저...",
    "그러니까",
    "뭐",
    "약간",
    "이제",
    "사실",
    "일단",
    "막",
    "좀",
]


class BasicAnalysisRequest(BaseModel):
    jobId: str
    videoPath: str
    videoDownloadUrl: str | None = None


TOTAL_ANALYSIS_STEPS = 9


def log_step(job_id: str, step_no: int, message: str) -> None:
    """분석 중 지금 무슨 작업을 하고 있는지 콘솔과 파일 로그에 기록합니다.

    영상 하나를 분석하는 데 시간이 꽤 걸리기 때문에, 이 로그가 없으면
    analysis-engine 터미널 화면이 아무 반응 없이 멈춘 것처럼 보입니다.
    """
    logger.info("(%s) %s/%s %s", job_id, step_no, TOTAL_ANALYSIS_STEPS, message)


def cleanup_temp_directory(job_id: str) -> None:
    project_root = resolve_project_root()
    temp_root = (project_root / "storage" / "temp").resolve()
    temp_directory = (temp_root / job_id).resolve()

    if temp_directory == temp_root or temp_root not in temp_directory.parents:
        logger.warning("(%s) 임시 파일 정리 경로가 허용 범위를 벗어나 건너뜁니다: %s", job_id, temp_directory)
        return

    if not temp_directory.exists():
        logger.info("(%s) 정리할 임시 파일이 없습니다: %s", job_id, temp_directory)
        return

    shutil.rmtree(temp_directory, ignore_errors=True)
    logger.info("(%s) 임시 파일 정리 완료: %s", job_id, temp_directory)


@router.post("/basic-analysis")
def basic_analysis(
        request: BasicAnalysisRequest,
        x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> Dict[str, Any]:
    with bind_job_id(request.jobId), bind_request_id(x_request_id):
        return _run_basic_analysis(request)


def _run_basic_analysis(request: BasicAnalysisRequest) -> Dict[str, Any]:
    job_id = request.jobId

    try:
        log_step(job_id, 1, "영상 파일 확인 중...")
        resolved_video_path = resolve_or_download_video_path(
            job_id,
            request.videoPath,
            request.videoDownloadUrl,
        )

        if resolved_video_path is None:
            log_step(job_id, 1, "영상 파일을 찾지 못해 분석을 중단합니다.")
            return create_failed_response(
                job_id=job_id,
                video_path=request.videoPath,
                reason="영상 파일을 찾을 수 없습니다.",
            )

        video_info = extract_video_info(resolved_video_path)

        if video_info["readable"] is False:
            log_step(job_id, 1, "영상 파일을 읽지 못해 분석을 중단합니다.")
            return create_failed_response(
                job_id=job_id,
                video_path=str(resolved_video_path),
                reason="영상 파일을 읽을 수 없습니다.",
            )

        log_step(job_id, 2, "영상에서 프레임(장면 이미지)을 추출하는 중...")
        frame_result = extract_sample_frames(
            job_id=job_id,
            video_path=resolved_video_path,
            fps=video_info["fps"],
            frame_count=video_info["frameCount"],
        )

        log_step(job_id, 3, "영상에서 오디오(소리)를 분리하는 중...")
        audio_extraction_result = extract_audio_from_video(
            job_id=job_id,
            video_path=resolved_video_path,
        )

        log_step(job_id, 4, "음성을 텍스트로 변환(STT)하는 중... (영상 길이에 따라 시간이 걸릴 수 있습니다)")
        stt_result = transcribe_audio(audio_extraction_result)

        log_step(job_id, 5, "자세(포즈)를 분석하는 중...")
        pose_result = analyze_pose_from_frames(frame_result["sampledFrames"])
        gesture_result = analyze_gesture_from_pose_result(pose_result)

        log_step(job_id, 6, "얼굴/시선/표정을 분석하는 중...")
        face_result = analyze_face_from_frames(frame_result["sampledFrames"])
        emotion_result = analyze_emotion_from_face_result(face_result)

        log_step(job_id, 7, "말하기 속도와 침묵 구간을 분석하는 중...")
        audio_result = analyze_speech(
            duration_sec=video_info["durationSec"],
            audio_extraction_result=audio_extraction_result,
            stt_result=stt_result,
        )

        filler_result = analyze_filler_from_transcript(
            stt_result=stt_result,
            audio_result=audio_result,
        )

        # 음성 점수는 필러 결과가 나온 뒤에야 확정할 수 있어 여기서 합칩니다.
        # (말속도 + 침묵 + 필러 + 음량 가중합)
        finalize_speech_score(audio_result, filler_result)

        log_step(job_id, 8, "항목별 점수와 최종 점수를 계산하는 중...")
        score_result = calculate_score(
            pose_result=pose_result,
            face_result=face_result,
            audio_result=audio_result,
            gesture_result=gesture_result,
            emotion_result=emotion_result,
        )

        log_step(job_id, 9, f"기본 분석 완료. 총점 {score_result['totalScore']}점")

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
            "gesture": gesture_result,
            "face": face_result,
            "emotion": emotion_result,
            "score": score_result,
        }
    finally:
        cleanup_temp_directory(job_id)


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


VIDEO_DOWNLOAD_TIMEOUT_SECONDS = 60
VIDEO_DOWNLOAD_CHUNK_SIZE_BYTES = 1024 * 1024
DEFAULT_ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB = 500


def resolve_analysis_engine_max_video_size_bytes() -> int:
    raw_value = os.getenv(
        "ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB",
        str(DEFAULT_ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB),
    ).strip()
    try:
        max_size_mb = int(raw_value)
    except ValueError as exception:
        raise RuntimeError(
            "ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB must be a positive integer."
        ) from exception

    if max_size_mb <= 0:
        raise RuntimeError(
            "ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB must be a positive integer."
        )

    return max_size_mb * 1024 * 1024


def resolve_or_download_video_path(
        job_id: str,
        video_path: str,
        video_download_url: str | None,
) -> Path | None:
    """videoDownloadUrl(MinIO presigned URL)이 있으면 먼저 그 영상을 내려받아 사용하고,
    URL이 없거나 다운로드에 실패하면 기존처럼 로컬 videoPath를 그대로 사용합니다.
    로컬 videoPath 기반 흐름은 이 함수를 거치지 않는 다른 코드에는 영향이 없습니다.
    """
    if video_download_url:
        downloaded_path = download_video_from_url(job_id, video_download_url, video_path)

        if downloaded_path is not None:
            return downloaded_path

        logger.warning(
            "(%s) MinIO 다운로드 URL에서 영상을 내려받지 못해 로컬 경로로 폴백합니다.",
            job_id,
        )

    return resolve_video_path(video_path)


def download_video_from_url(
        job_id: str,
    video_download_url: str,
    original_video_path: str,
) -> Path | None:
    download_path: Path | None = None
    response = None
    try:
        project_root = resolve_project_root()
        download_directory = project_root / "storage" / "temp" / job_id / "download"
        download_directory.mkdir(parents=True, exist_ok=True)

        extension = Path(original_video_path).suffix or ".mp4"
        download_path = download_directory / f"original{extension}"
        max_size = resolve_analysis_engine_max_video_size_bytes()

        response = requests.get(
            video_download_url,
            stream=True,
            timeout=VIDEO_DOWNLOAD_TIMEOUT_SECONDS,
        )
        response.raise_for_status()

        content_length = response.headers.get("content-length")
        if content_length is not None and int(content_length) > max_size:
            raise ValueError(
                "Downloaded video exceeds ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB "
                f"({content_length} bytes > {max_size} bytes)."
            )

        downloaded_size = 0
        with open(download_path, "wb") as video_file:
            for chunk in response.iter_content(chunk_size=VIDEO_DOWNLOAD_CHUNK_SIZE_BYTES):
                if chunk:
                    downloaded_size += len(chunk)
                    if downloaded_size > max_size:
                        raise ValueError(
                            "Downloaded video exceeds ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB "
                            f"({downloaded_size} bytes > {max_size} bytes)."
                        )
                    video_file.write(chunk)

        if downloaded_size == 0:
            raise ValueError("Downloaded video is empty.")

        return download_path.resolve()

    except Exception as exception:
        if download_path is not None:
            download_path.unlink(missing_ok=True)
        logger.warning("(%s) MinIO 다운로드 URL 요청 실패: %s", job_id, exception)
        return None
    finally:
        if response is not None:
            response.close()


def resolve_project_root() -> Path:
    current_path = Path.cwd().resolve()

    if current_path.name == "analysis-engine":
        return current_path.parent

    if (current_path / "storage").exists():
        return current_path

    if (current_path.parent / "storage").exists():
        return current_path.parent

    return current_path.parent


def create_mediapipe_image_from_frame_path(frame_path: str) -> mp.Image | None:
    image = cv2.imread(frame_path)

    if image is None:
        return None

    rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

    return mp.Image(
        image_format=mp.ImageFormat.SRGB,
        data=rgb_image,
    )


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


FFMPEG_AUDIO_EXTRACTION_TIMEOUT_SECONDS = 120


def extract_audio_from_video(
        job_id: str,
        video_path: Path,
) -> Dict[str, Any]:
    project_root = resolve_project_root()
    audio_directory = project_root / "storage" / "temp" / job_id / "audio"
    audio_directory.mkdir(parents=True, exist_ok=True)

    audio_path = audio_directory / "audio.wav"

    ffmpeg_executable = imageio_ffmpeg.get_ffmpeg_exe()

    command = [
        ffmpeg_executable,
        "-y",
        "-i",
        str(video_path),
        "-vn",
        "-acodec",
        "pcm_s16le",
        "-ar",
        "16000",
        "-ac",
        "1",
        str(audio_path),
    ]

    try:
        completed_process = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
            timeout=FFMPEG_AUDIO_EXTRACTION_TIMEOUT_SECONDS,
        )

        if completed_process.returncode != 0:
            return {
                "success": False,
                "audioPath": "",
                "fileSize": 0,
                "sampleRate": 16000,
                "channelCount": 1,
                "codec": "pcm_s16le",
                "error": completed_process.stderr[-1000:],
            }

        if not audio_path.exists() or audio_path.stat().st_size == 0:
            return {
                "success": False,
                "audioPath": "",
                "fileSize": 0,
                "sampleRate": 16000,
                "channelCount": 1,
                "codec": "pcm_s16le",
                "error": "오디오 파일이 생성되지 않았습니다.",
            }

        return {
            "success": True,
            "audioPath": str(audio_path),
            "fileSize": audio_path.stat().st_size,
            "sampleRate": 16000,
            "channelCount": 1,
            "codec": "pcm_s16le",
            "error": "",
        }

    except Exception as exception:
        return {
            "success": False,
            "audioPath": "",
            "fileSize": 0,
            "sampleRate": 16000,
            "channelCount": 1,
            "codec": "pcm_s16le",
            "error": str(exception),
        }


def transcribe_audio(audio_extraction_result: Dict[str, Any]) -> Dict[str, Any]:
    if not audio_extraction_result.get("success"):
        return create_empty_stt_result(
            reason="오디오 추출에 실패하여 STT를 수행하지 못했습니다.",
        )

    audio_path = audio_extraction_result.get("audioPath", "")

    if not audio_path or not Path(audio_path).exists():
        return create_empty_stt_result(
            reason="STT 대상 오디오 파일을 찾을 수 없습니다.",
        )

    try:
        with model_registry.whisper_model_context() as model:
            segments_generator, info = model.transcribe(
                audio_path,
                language="ko",
                beam_size=5,
                vad_filter=True,
            )

            segments: List[Dict[str, Any]] = []
            full_text_parts: List[str] = []

            for segment in segments_generator:
                text = segment.text.strip()

                if text:
                    full_text_parts.append(text)

                segments.append(
                    {
                        "start": round(segment.start, 2),
                        "end": round(segment.end, 2),
                        "duration": round(segment.end - segment.start, 2),
                        "text": text,
                    }
                )

            transcript = " ".join(full_text_parts).strip()

        return {
            "success": True,
            "analysisMethod": "faster_whisper",
            "modelSize": WHISPER_MODEL_SIZE,
            "language": info.language,
            "languageProbability": round(info.language_probability, 4),
            "transcript": transcript,
            "segments": segments,
            "segmentCount": len(segments),
            "wordCount": count_words_for_presentation(transcript),
            "error": "",
        }

    except Exception as exception:
        return create_empty_stt_result(
            reason=str(exception),
        )


def create_empty_stt_result(reason: str) -> Dict[str, Any]:
    return {
        "success": False,
        "analysisMethod": "faster_whisper",
        "modelSize": WHISPER_MODEL_SIZE,
        "language": "unknown",
        "languageProbability": 0,
        "transcript": "",
        "segments": [],
        "segmentCount": 0,
        "wordCount": 0,
        "error": reason,
    }


def count_words_for_presentation(text: str) -> int:
    normalized_text = text.strip()

    if not normalized_text:
        return 0

    return len(normalized_text.split())


def analyze_pose_from_frames(
        sampled_frames: List[Dict[str, Any]]
) -> Dict[str, Any]:
    if not sampled_frames:
        return create_empty_pose_result()

    analyzed_frames: List[Dict[str, Any]] = []
    detected_count = 0
    shoulder_balance_scores: List[int] = []
    shoulder_diffs: List[float] = []

    with model_registry.pose_landmarker_context() as landmarker:
        for frame_info in sampled_frames:
            frame_path = frame_info.get("framePath")

            if not frame_path:
                analyzed_frames.append(create_pose_frame_result(frame_info, False))
                continue

            mp_image = create_mediapipe_image_from_frame_path(frame_path)

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

    average_shoulder_score = calculate_average_int(shoulder_balance_scores)
    average_shoulder_diff = calculate_average_float(shoulder_diffs)

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


def create_face_landmark_dict(landmark: Any) -> Dict[str, float]:
    return {
        "x": round(float(getattr(landmark, "x", 0)), 4),
        "y": round(float(getattr(landmark, "y", 0)), 4),
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


def analyze_gesture_from_pose_result(
        pose_result: Dict[str, Any]
) -> Dict[str, Any]:
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

        if not all([left_shoulder, right_shoulder, left_elbow, right_elbow, left_wrist, right_wrist]):
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
                "leftWristMovement": round(left_movement, 4) if left_movement is not None else None,
                "rightWristMovement": round(right_movement, 4) if right_movement is not None else None,
            }
        )

        previous_left_wrist = left_wrist
        previous_right_wrist = right_wrist

    total_pose_frames = len(frame_results)
    gesture_rate = round(active_count / total_pose_frames, 4) if total_pose_frames > 0 else 0
    hand_visibility_rate = round(
        visible_hand_count / (total_pose_frames * 2),
        4,
        ) if total_pose_frames > 0 else 0

    average_movement = calculate_average_float(movement_distances)
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

    if previous_wrist.get("visibility", 0) < 0.5 or current_wrist.get("visibility", 0) < 0.5:
        return None

    x_diff = current_wrist.get("x", 0) - previous_wrist.get("x", 0)
    y_diff = current_wrist.get("y", 0) - previous_wrist.get("y", 0)

    return (x_diff ** 2 + y_diff ** 2) ** 0.5


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


def analyze_face_from_frames(
        sampled_frames: List[Dict[str, Any]]
) -> Dict[str, Any]:
    if not sampled_frames:
        return create_empty_face_result()

    analyzed_frames: List[Dict[str, Any]] = []
    detected_count = 0
    gaze_scores: List[int] = []
    camera_gaze_frame_count = 0

    with model_registry.face_landmarker_context() as landmarker:
        for frame_info in sampled_frames:
            frame_path = frame_info.get("framePath")

            if not frame_path:
                analyzed_frames.append(create_face_frame_result(frame_info, False))
                continue

            mp_image = create_mediapipe_image_from_frame_path(frame_path)

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

            # 눈동자(Iris) 중심이 눈 영역 안에서 어디쯤 있는지를 0~1 비율로 계산합니다.
            # 0.5에 가까울수록 눈동자가 눈 정중앙, 즉 정면(카메라 방향)을 보고 있다는 뜻입니다.
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

            horizontal_ratio, vertical_ratio = average_gaze_ratios(
                left_gaze_ratio,
                right_gaze_ratio,
            )

            gaze_score = calculate_gaze_score_from_ratios(horizontal_ratio, vertical_ratio)
            gazing_at_camera = is_gazing_at_camera(horizontal_ratio, vertical_ratio)

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

            gaze_scores.append(gaze_score)

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
    average_gaze_score = calculate_average_int(gaze_scores)
    # 문서(점수화 알고리즘 선정 자료)가 권장하는 "카메라 응시 비율" 방식입니다.
    # 얼굴이 검출된 프레임 중 카메라를 본 프레임의 비율을 최종 시선 점수의 기준으로 삼습니다.
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


def analyze_emotion_from_face_result(
        face_result: Dict[str, Any]
) -> Dict[str, Any]:
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
    detected_frame_count = sum(1 for frame in frame_results if frame.get("faceDetected"))
    detection_rate = round(detected_frame_count / total_frames, 4) if total_frames > 0 else 0

    expression_raw_score = calculate_average_int(expressiveness_scores)
    expression_variety_score = calculate_expression_variety_score(emotion_counts)

    # '발표_코칭_점수화_알고리즘_선정_자료'의 "4. 표정 점수(Facial Landmark Distance Analysis)"에
    # 해당하는 점수입니다. 최종 가중합에 직접 반영되는 표정 항목입니다.
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

    if not filtered_counts:
        return "unknown"

    return max(filtered_counts, key=filtered_counts.get)


def analyze_speech(
        duration_sec: float,
        audio_extraction_result: Dict[str, Any],
        stt_result: Dict[str, Any],
) -> Dict[str, Any]:
    if stt_result.get("success"):
        return analyze_speech_from_stt(
            duration_sec=duration_sec,
            audio_extraction_result=audio_extraction_result,
            stt_result=stt_result,
        )

    return analyze_speech_from_video_duration(
        duration_sec=duration_sec,
        audio_extraction_result=audio_extraction_result,
        stt_result=stt_result,
    )


def analyze_speech_from_stt(
        duration_sec: float,
        audio_extraction_result: Dict[str, Any],
        stt_result: Dict[str, Any],
) -> Dict[str, Any]:
    safe_duration_sec = max(duration_sec, 0)

    segments = stt_result.get("segments", [])
    word_count = int(stt_result.get("wordCount", 0))

    speech_duration_sec = calculate_segment_speech_duration(segments)
    pause_analysis = analyze_pauses_from_segments(
        segments=segments,
        total_duration_sec=safe_duration_sec,
    )

    speech_speed_wpm = calculate_speech_speed_wpm(
        word_count=word_count,
        speech_duration_sec=speech_duration_sec,
    )

    speech_speed_score = calculate_speech_speed_score(speech_speed_wpm)
    silence_score = calculate_silence_score(pause_analysis["silenceRatio"])

    return {
        "analysisMethod": "stt_based_analysis",
        "audioExtraction": audio_extraction_result,
        "stt": stt_result,
        "durationSec": safe_duration_sec,
        "estimatedSpeechDurationSec": speech_duration_sec,
        "estimatedPauseDurationSec": pause_analysis["totalSilenceTime"],
        "estimatedWordCount": word_count,
        "speechSpeedWpm": speech_speed_wpm,
        "speechSpeedScore": speech_speed_score,
        "silenceCount": pause_analysis["silenceCount"],
        "totalSilenceTime": pause_analysis["totalSilenceTime"],
        "silenceRatio": pause_analysis["silenceRatio"],
        "silenceScore": silence_score,
        "volumeStabilityScore": VOLUME_STABILITY_BASELINE_SCORE,
        # speechScore는 finalize_speech_score()에서 말속도/침묵/필러/음량을 합쳐 확정합니다.
        "speechScore": 0,
        "note": "faster-whisper STT 결과를 기반으로 말하기 속도와 침묵 구간을 계산했습니다.",
    }


def analyze_speech_from_video_duration(
        duration_sec: float,
        audio_extraction_result: Dict[str, Any],
        stt_result: Dict[str, Any],
) -> Dict[str, Any]:
    safe_duration_sec = max(duration_sec, 0)
    estimated_speech_duration_sec = round(safe_duration_sec * 0.82, 2)
    estimated_pause_duration_sec = round(
        safe_duration_sec - estimated_speech_duration_sec,
        2,
        )

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

    return {
        "analysisMethod": "audio_extracted_duration_based_estimation",
        "audioExtraction": audio_extraction_result,
        "stt": stt_result,
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
        "volumeStabilityScore": VOLUME_STABILITY_BASELINE_SCORE,
        # speechScore는 finalize_speech_score()에서 말속도/침묵/필러/음량을 합쳐 확정합니다.
        "speechScore": 0,
        "note": "오디오는 추출했지만 STT에 실패하여 영상 길이 기반 추정값을 사용했습니다.",
    }


def blend_speech_score(
        speech_speed_score: int,
        silence_score: int,
        filler_score: int,
        volume_stability_score: int,
) -> int:
    """말속도/침묵/필러/음량 안정성 점수를 문서 권장 가중치로 합칩니다.

    가중치: 말속도 35% + 침묵 25% + 필러 25% + 음량 안정성 15%.
    """
    blended = int(
        speech_speed_score * SPEECH_SPEED_WEIGHT
        + silence_score * SILENCE_WEIGHT
        + filler_score * FILLER_WEIGHT
        + volume_stability_score * VOLUME_STABILITY_WEIGHT
    )

    return max(0, min(blended, 100))


def finalize_speech_score(
        audio_result: Dict[str, Any],
        filler_result: Dict[str, Any],
) -> Dict[str, Any]:
    """필러 분석이 끝난 뒤 음성 점수를 최종 확정합니다.

    필러 점수는 필러 분석 이후에야 알 수 있어, 자세/시선처럼 즉시 계산하지 않고
    이 함수에서 마지막으로 합칩니다. 음량 안정성은 아직 미구현이라 중립 기본값을 씁니다.
    audio_result 딕셔너리를 직접 수정하고 그대로 반환합니다.
    """
    speech_speed_score = int(audio_result.get("speechSpeedScore", 0))
    silence_score = int(audio_result.get("silenceScore", 0))
    filler_score = int(filler_result.get("fillerScore", 0))
    volume_stability_score = int(
        audio_result.get("volumeStabilityScore", VOLUME_STABILITY_BASELINE_SCORE)
    )

    speech_score = blend_speech_score(
        speech_speed_score=speech_speed_score,
        silence_score=silence_score,
        filler_score=filler_score,
        volume_stability_score=volume_stability_score,
    )

    audio_result["fillerScore"] = filler_score
    audio_result["volumeStabilityScore"] = volume_stability_score
    audio_result["volumeStabilityImplemented"] = VOLUME_STABILITY_IMPLEMENTED
    audio_result["speechScore"] = speech_score
    audio_result["speechScoreWeights"] = {
        "speechSpeed": SPEECH_SPEED_WEIGHT,
        "silence": SILENCE_WEIGHT,
        "filler": FILLER_WEIGHT,
        "volumeStability": VOLUME_STABILITY_WEIGHT,
    }

    return audio_result


def calculate_segment_speech_duration(segments: List[Dict[str, Any]]) -> float:
    total_duration = 0.0

    for segment in segments:
        duration = float(segment.get("duration", 0))
        total_duration += max(duration, 0)

    return round(total_duration, 2)


def analyze_pauses_from_segments(
        segments: List[Dict[str, Any]],
        total_duration_sec: float,
) -> Dict[str, Any]:
    if not segments or total_duration_sec <= 0:
        return {
            "silenceCount": 0,
            "totalSilenceTime": 0,
            "silenceRatio": 0,
        }

    sorted_segments = sorted(
        segments,
        key=lambda segment: float(segment.get("start", 0)),
    )

    silence_threshold_sec = 1.0
    silence_count = 0
    total_silence_time = 0.0

    previous_end = 0.0

    for segment in sorted_segments:
        start = float(segment.get("start", 0))
        end = float(segment.get("end", 0))

        gap = max(start - previous_end, 0)

        if gap >= silence_threshold_sec:
            silence_count += 1
            total_silence_time += gap

        previous_end = max(previous_end, end)

    tail_gap = max(total_duration_sec - previous_end, 0)

    if tail_gap >= silence_threshold_sec:
        silence_count += 1
        total_silence_time += tail_gap

    total_silence_time = round(total_silence_time, 2)
    silence_ratio = calculate_silence_ratio(
        silence_duration_sec=total_silence_time,
        duration_sec=total_duration_sec,
    )

    return {
        "silenceCount": silence_count,
        "totalSilenceTime": total_silence_time,
        "silenceRatio": silence_ratio,
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


def analyze_filler_from_transcript(
        stt_result: Dict[str, Any],
        audio_result: Dict[str, Any],
) -> Dict[str, Any]:
    transcript = stt_result.get("transcript", "")
    estimated_word_count = int(audio_result.get("estimatedWordCount", 0))

    if stt_result.get("success") and transcript:
        filler_detail = count_filler_words(transcript)
        filler_count = filler_detail["totalCount"]
        filler_ratio = calculate_filler_ratio(
            filler_count=filler_count,
            estimated_word_count=max(estimated_word_count, 1),
        )

        filler_score = calculate_filler_score(filler_ratio)

        return {
            "analysisMethod": "stt_based_filler_detection",
            "fillerWords": filler_detail["items"],
            "fillerCount": filler_count,
            "fillerRatio": filler_ratio,
            "fillerScore": filler_score,
            "note": "STT 변환 텍스트에서 한국어 필러 표현을 탐지했습니다.",
        }

    filler_count = estimate_filler_count(
        estimated_word_count=estimated_word_count,
        duration_sec=float(audio_result.get("durationSec", 0)),
    )

    filler_ratio = calculate_filler_ratio(
        filler_count=filler_count,
        estimated_word_count=estimated_word_count,
    )

    filler_score = calculate_filler_score(filler_ratio)

    return {
        "analysisMethod": "duration_based_estimation",
        "fillerWords": [],
        "fillerCount": filler_count,
        "fillerRatio": filler_ratio,
        "fillerScore": filler_score,
        "note": "STT 결과가 없어 필러 수를 추정값으로 계산했습니다.",
    }


def count_filler_words(transcript: str) -> Dict[str, Any]:
    items: List[Dict[str, Any]] = []
    total_count = 0

    normalized_transcript = transcript.replace(",", " ").replace(".", " ")
    tokens = normalized_transcript.split()

    for filler_word in KOREAN_FILLER_WORDS:
        count = tokens.count(filler_word)

        if count <= 0:
            continue

        items.append(
            {
                "word": filler_word,
                "count": count,
            }
        )

        total_count += count

    return {
        "totalCount": total_count,
        "items": items,
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


def calculate_iris_gaze_ratio(
        iris_center: Any,
        eye_horizontal_a: Any,
        eye_horizontal_b: Any,
        eye_top: Any,
        eye_bottom: Any,
) -> Dict[str, float] | None:
    """눈동자(iris) 중심이 눈 영역 안에서 어디에 위치하는지를 0~1 비율로 계산합니다.

    0.5, 0.5는 눈 정중앙(정면 응시)을 의미합니다.
    """
    horizontal_span = abs(eye_horizontal_a.x - eye_horizontal_b.x)
    vertical_span = abs(eye_bottom.y - eye_top.y)

    if horizontal_span <= 0 or vertical_span <= 0:
        return None

    left_bound_x = min(eye_horizontal_a.x, eye_horizontal_b.x)
    top_bound_y = min(eye_top.y, eye_bottom.y)

    horizontal_ratio = (iris_center.x - left_bound_x) / horizontal_span
    vertical_ratio = (iris_center.y - top_bound_y) / vertical_span

    return {
        "horizontalRatio": horizontal_ratio,
        "verticalRatio": vertical_ratio,
    }


def average_gaze_ratios(
        left_gaze_ratio: Dict[str, float] | None,
        right_gaze_ratio: Dict[str, float] | None,
) -> tuple[float, float]:
    ratios = [ratio for ratio in (left_gaze_ratio, right_gaze_ratio) if ratio is not None]

    if not ratios:
        return GAZE_CAMERA_CENTER, GAZE_CAMERA_CENTER

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


def calculate_gaze_score_from_ratios(horizontal_ratio: float, vertical_ratio: float) -> int:
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
    """'발표_코칭_점수화_알고리즘_선정_자료'의 시선 점수화 기준을 따릅니다.

    카메라 응시 비율 70% 이상: 우수, 40~70%: 보통, 40% 미만: 시선 개선 필요.
    """
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
        gesture_result: Dict[str, Any],
        emotion_result: Dict[str, Any],
) -> Dict[str, Any]:
    # '발표_코칭_점수화_알고리즘_선정_자료'의 "9. 최종 점수화" 기준 가중치입니다.
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

    # '발표_코칭_점수화_알고리즘_선정_자료'의 "최종 점수 = 가중합 − 전체 패널티" 기준입니다.
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
    """탐지 신뢰도와 영상 상태를 기준으로 총점에서 뺄 '전체 패널티'를 계산합니다.

    - 자세/얼굴 검출률이 낮을수록 감점(50% 미만 5점, 70% 미만 2점)
    - STT 실패로 추정값을 쓴 경우 3점
    - 영상이 너무 짧은 경우(10초 미만) 5점
    패널티는 최대 MAX_TOTAL_PENALTY점으로 제한합니다.
    """
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


def create_empty_audio_result() -> Dict[str, Any]:
    empty_stt = create_empty_stt_result(
        reason="영상 정보를 읽지 못해 STT를 수행하지 못했습니다.",
    )

    return {
        "analysisMethod": "stt_based_analysis",
        "audioExtraction": {
            "success": False,
            "audioPath": "",
            "fileSize": 0,
            "sampleRate": 16000,
            "channelCount": 1,
            "codec": "pcm_s16le",
            "error": "영상 정보를 읽지 못해 오디오를 추출하지 못했습니다.",
        },
        "stt": empty_stt,
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
        "volumeStabilityScore": 0,
        "fillerScore": 0,
        "speechScore": 0,
        "note": "영상 정보를 읽지 못해 음성 분석을 수행하지 못했습니다.",
    }


def create_empty_filler_result() -> Dict[str, Any]:
    return {
        "analysisMethod": "stt_based_filler_detection",
        "fillerWords": [],
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
        "gesture": create_empty_gesture_result(),
        "face": create_empty_face_result(),
        "emotion": create_empty_emotion_result(),
        "score": {
            "totalScore": 0,
            "postureScore": 0,
            "gazeScore": 0,
            "speechScore": 0,
            "gestureScore": 0,
            "expressionScore": 0,
        },
        "error": {
            "reason": reason,
        },
    }
