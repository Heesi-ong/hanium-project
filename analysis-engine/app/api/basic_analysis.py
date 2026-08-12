import logging
from typing import Any, Dict

from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel, Field

from app.core.logging_config import bind_job_id, bind_request_id
from app.core.security import verify_internal_api_key
from app.services import (
    audio_analysis,
    face_analysis,
    media_io,
    pose_analysis,
    scoring,
    speech_to_text,
)

logger = logging.getLogger("analysis-engine")

router = APIRouter(
    prefix="/api",
    tags=["basic-analysis"],
    dependencies=[Depends(verify_internal_api_key)],
)


# backend의 JobIdGenerator가 항상 이 형식(timestamp-uuid8)으로만 jobId를 만들기 때문에
# 지금은 실제로 악용 가능한 경로는 아니지만, 이 엔진이 jobId를 그대로 파일시스템 경로에
# 사용하는 여러 곳(download/frame/audio 임시 디렉터리 등)에는 cleanup_temp_directory처럼
# 경로 이탈(path traversal) 방지 검사가 없다. 요청 진입점에서 형식을 강제해두면, 호출자가
# 바뀌거나 ID 생성 로직이 바뀌어도 그 방어를 각 파일마다 따로 구현할 필요가 없다.
JOB_ID_PATTERN = r"^\d{14}-[0-9a-f]{8}$"


class BasicAnalysisRequest(BaseModel):
    jobId: str = Field(pattern=JOB_ID_PATTERN)
    videoPath: str
    videoDownloadUrl: str | None = None


TOTAL_ANALYSIS_STEPS = 9


def log_step(job_id: str, step_no: int, message: str) -> None:
    """분석 중 지금 무슨 작업을 하고 있는지 콘솔과 파일 로그에 기록합니다.

    영상 하나를 분석하는 데 시간이 꽤 걸리기 때문에, 이 로그가 없으면
    analysis-engine 터미널 화면이 아무 반응 없이 멈춘 것처럼 보입니다.
    """
    logger.info("(%s) %s/%s %s", job_id, step_no, TOTAL_ANALYSIS_STEPS, message)


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
        resolved_video_path = media_io.resolve_or_download_video_path(
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

        video_info = media_io.extract_video_info(resolved_video_path)

        if video_info["readable"] is False:
            log_step(job_id, 1, "영상 파일을 읽지 못해 분석을 중단합니다.")
            return create_failed_response(
                job_id=job_id,
                video_path=str(resolved_video_path),
                reason="영상 파일을 읽을 수 없습니다.",
            )

        log_step(job_id, 2, "영상에서 프레임(장면 이미지)을 추출하는 중...")
        frame_result = media_io.extract_sample_frames(
            job_id=job_id,
            video_path=resolved_video_path,
            fps=video_info["fps"],
            frame_count=video_info["frameCount"],
        )

        log_step(job_id, 3, "영상에서 오디오(소리)를 분리하는 중...")
        audio_extraction_result = media_io.extract_audio_from_video(
            job_id=job_id,
            video_path=resolved_video_path,
        )

        log_step(
            job_id,
            4,
            "음성을 텍스트로 변환(STT)하는 중... (영상 길이에 따라 시간이 걸릴 수 있습니다)",
        )
        stt_result = speech_to_text.transcribe_audio(audio_extraction_result)

        sampled_frames = frame_result["sampledFrames"]
        mp_images = media_io.preload_mediapipe_images(sampled_frames)

        log_step(job_id, 5, "자세(포즈)를 분석하는 중...")
        pose_result = pose_analysis.analyze_pose_from_frames(sampled_frames, mp_images)
        gesture_result = pose_analysis.analyze_gesture_from_pose_result(pose_result)

        log_step(job_id, 6, "얼굴/시선/표정을 분석하는 중...")
        face_result = face_analysis.analyze_face_from_frames(sampled_frames, mp_images)
        emotion_result = face_analysis.analyze_emotion_from_face_result(face_result)

        log_step(job_id, 7, "말하기 속도와 침묵 구간을 분석하는 중...")
        audio_result = audio_analysis.analyze_speech(
            duration_sec=video_info["durationSec"],
            audio_extraction_result=audio_extraction_result,
            stt_result=stt_result,
        )

        filler_result = audio_analysis.analyze_filler_from_transcript(
            stt_result=stt_result,
            audio_result=audio_result,
        )

        # 음성 점수는 필러 결과가 나온 뒤에야 확정할 수 있어 여기서 합칩니다.
        # (말속도 + 침묵 + 필러 + 음량 가중합)
        audio_analysis.finalize_speech_score(audio_result, filler_result)

        log_step(job_id, 8, "항목별 점수와 최종 점수를 계산하는 중...")
        score_result = scoring.calculate_score(
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
        media_io.cleanup_temp_directory(job_id)


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
        "audio": audio_analysis.create_empty_audio_result(),
        "filler": audio_analysis.create_empty_filler_result(),
        "pose": pose_analysis.create_empty_pose_result(),
        "gesture": pose_analysis.create_empty_gesture_result(),
        "face": face_analysis.create_empty_face_result(),
        "emotion": face_analysis.create_empty_emotion_result(),
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
