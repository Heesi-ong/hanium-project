import logging
from typing import Any, Dict

from fastapi import APIRouter, Depends, Header
from app.core.logging_config import bind_job_id, bind_request_id
from app.core.security import verify_internal_api_key
from app.api.schemas import BasicAnalysisRequest, BasicAnalysisResponse
from app.services import (
    audio_analysis,
    face_analysis,
    frame_overlay,
    media_io,
    pose_analysis,
    progress_file,
    scoring,
    speech_to_text,
)
from app.services.analysis_trace import AnalysisTrace

logger = logging.getLogger("analysis-engine")

router = APIRouter(
    prefix="/api",
    tags=["basic-analysis"],
    dependencies=[Depends(verify_internal_api_key)],
)


TOTAL_ANALYSIS_STEPS = 9


def log_step(job_id: str, step_no: int, message: str) -> None:
    """분석 중 지금 무슨 작업을 하고 있는지 콘솔과 파일 로그에 기록합니다.

    영상 하나를 분석하는 데 시간이 꽤 걸리기 때문에, 이 로그가 없으면
    analysis-engine 터미널 화면이 아무 반응 없이 멈춘 것처럼 보입니다.
    """
    logger.info("(%s) %s/%s %s", job_id, step_no, TOTAL_ANALYSIS_STEPS, message)


@router.post(
    "/basic-analysis",
    response_model=BasicAnalysisResponse,
    response_model_exclude_none=True,
)
def basic_analysis(
    request: BasicAnalysisRequest,
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> Dict[str, Any]:
    with bind_job_id(request.jobId), bind_request_id(x_request_id):
        return _run_basic_analysis(request)


def _run_basic_analysis(request: BasicAnalysisRequest) -> Dict[str, Any]:
    job_id = request.jobId
    trace = AnalysisTrace(TOTAL_ANALYSIS_STEPS)

    def step(step_no: int, key: str, message: str) -> None:
        """단계 로그 + 트레이스 기록 + progress.json 갱신을 한 번에 처리합니다."""
        log_step(job_id, step_no, message)
        trace.step(step_no, key, message)
        progress_file.write_basic_progress(
            job_id, step_no, TOTAL_ANALYSIS_STEPS, key, message
        )

    try:
        step(1, "video_check", "영상 파일 확인 중...")
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

        step(2, "frame_extract", "영상에서 프레임(장면 이미지)을 추출하는 중...")
        frame_result = media_io.extract_sample_frames(
            job_id=job_id,
            video_path=resolved_video_path,
            fps=video_info["fps"],
            frame_count=video_info["frameCount"],
        )
        trace.detail(
            f"{video_info['durationSec']}초 영상에서 프레임 "
            f"{frame_result['savedCount']}장 추출 "
            f"({video_info['width']}x{video_info['height']}, {video_info['fps']}fps)"
        )

        step(3, "audio_extract", "영상에서 오디오(소리)를 분리하는 중...")
        audio_extraction_result = media_io.extract_audio_from_video(
            job_id=job_id,
            video_path=resolved_video_path,
        )
        trace.detail(
            "오디오 분리 완료 (16kHz 모노 WAV)"
            if audio_extraction_result.get("success")
            else "오디오 분리 실패 — 음성 분석은 영상 길이 기준으로 대체합니다."
        )

        step(
            4,
            "speech_to_text",
            "음성을 텍스트로 변환(STT)하는 중... (영상 길이에 따라 시간이 걸릴 수 있습니다)",
        )
        stt_result = speech_to_text.transcribe_audio(audio_extraction_result)
        trace.detail(
            f"STT 인식 텍스트 {len(str(stt_result.get('transcript', '')))}자, "
            f"세그먼트 {len(stt_result.get('segments', []) or [])}개"
        )

        sampled_frames = frame_result["sampledFrames"]
        mp_images = media_io.preload_mediapipe_images(sampled_frames)

        step(5, "pose_gesture", "자세(포즈)와 제스처를 분석하는 중... (MediaPipe Pose Landmarker)")
        pose_result = pose_analysis.analyze_pose_from_frames(sampled_frames, mp_images)
        gesture_result = pose_analysis.analyze_gesture_from_pose_result(pose_result)
        frame_overlays = _build_frame_overlays_safely(
            job_id, sampled_frames, pose_result, gesture_result
        )
        trace.detail(
            f"포즈 검출 {pose_result.get('detectedFrameCount', 0)}/"
            f"{pose_result.get('totalFrameCount', 0)} 프레임, "
            f"제스처 활성 {gesture_result.get('gestureFrameCount', 0)} 프레임, "
            f"스켈레톤 오버레이 {len(frame_overlays)}장 생성"
        )

        step(6, "face_emotion", "얼굴/시선/표정을 분석하는 중...")
        face_result = face_analysis.analyze_face_from_frames(sampled_frames, mp_images)
        emotion_result = face_analysis.analyze_emotion_from_face_result(face_result)
        trace.detail(
            f"얼굴 검출 {face_result.get('detectedFrameCount', 0)}/"
            f"{face_result.get('totalFrameCount', 0)} 프레임 "
            "(시선·표정은 참고용으로만 표시하고 점수에는 반영하지 않습니다)"
        )

        step(7, "speech_metrics", "말하기 속도와 침묵 구간을 분석하는 중...")
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

        step(8, "scoring", "항목별 점수와 최종 점수를 계산하는 중...")
        score_result = scoring.calculate_score(
            pose_result=pose_result,
            face_result=face_result,
            audio_result=audio_result,
            gesture_result=gesture_result,
            emotion_result=emotion_result,
        )
        trace.detail(
            f"자세 {score_result.get('postureScore', 0)} · "
            f"음성 {score_result.get('speechScore', 0)} · "
            f"제스처 {score_result.get('gestureScore', 0)} → "
            f"총점 {score_result.get('totalScore', 0)} (weighted-v2)"
        )

        step(9, "completed", f"기본 분석 완료. 총점 {score_result['totalScore']}점")
        trace.finish()

        return {
            "jobId": request.jobId,
            "status": "success",
            "analysisTrace": trace.to_list(),
            "frameOverlays": frame_overlays,
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


def _build_frame_overlays_safely(
    job_id: str,
    sampled_frames: list,
    pose_result: Dict[str, Any],
    gesture_result: Dict[str, Any],
) -> list:
    """오버레이 생성은 사용자에게 보여주기 위한 부가 기능이므로, 실패하더라도
    분석 응답 자체는 정상 반환되어야 합니다."""
    try:
        return frame_overlay.build_frame_overlays(
            sampled_frames,
            pose_result.get("frameResults", []),
            gesture_result.get("frameResults", []),
        )
    except Exception:  # noqa: BLE001
        logger.exception("(%s) 프레임 오버레이 생성에 실패해 빈 목록으로 진행합니다.", job_id)
        return []


def create_failed_response(
    job_id: str,
    video_path: str,
    reason: str,
) -> Dict[str, Any]:
    return {
        "jobId": job_id,
        "status": "failed",
        "analysisTrace": [],
        "frameOverlays": [],
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
