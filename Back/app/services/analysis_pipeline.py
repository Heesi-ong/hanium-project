"""단일 분석 작업의 프레임/음성/자세/얼굴/제스처 분석과 결과 저장 순서를 실행한다."""

import logging
import time

from ..config import ANALYSIS_ALGORITHM_VERSION, FRAME_DIR, OLLAMA_MODEL, RESULT_DIR, UPLOAD_DIR
from ..services.analysis_jobs import (
    clear_source_file,
    is_cancel_requested,
    mark_job_cancelled,
    mark_job_completed,
    mark_job_failed,
    update_job_progress,
)
from ..services.audio_analyzer import analyze_audio_from_video
from ..services.face_analyzer import analyze_face_from_frame, create_face_landmarker
from ..services.feedback_generator import generate_feedback
from ..services.file_cleaner import ensure_file_removed, safe_remove_directory
from ..services.filler_analyzer import analyze_filler_words
from ..services.frame_extractor import extract_frames
from ..services.gesture_analyzer import analyze_gesture_from_pose_results
from ..services.log_safety import safe_log_identifier
from ..services.pose_analyzer import analyze_pose_from_frame, create_pose_landmarker
from ..services.result_saver import delete_analysis_result, save_analysis_result
from ..services.score_calculator import calculate_basic_score
from ..services.timeline_analyzer import analyze_timeline_scores
from ..services.video_info import get_video_info
from ..services.volume_analyzer import analyze_volume_from_video

logger = logging.getLogger(__name__)
PUBLIC_ANALYSIS_ERROR = "영상 분석을 완료하지 못했습니다. 파일 상태를 확인한 뒤 다시 시도해주세요."


class AnalysisCancelled(Exception):
    pass


def _check_cancelled(job_id, heartbeat=None):
    if heartbeat:
        heartbeat()
    if is_cancel_requested(job_id):
        raise AnalysisCancelled()


def _analyze_frames(job_id, frame_paths, analyzer_factory, analyzer, stage, start, end, heartbeat=None):
    results = []
    total = max(1, len(frame_paths))
    last_progress = None
    with analyzer_factory() as landmarker:
        for index, frame_path in enumerate(frame_paths):
            _check_cancelled(job_id, heartbeat)
            results.append(analyzer(frame_path, landmarker))
            progress = start + int(((index + 1) / total) * (end - start))
            if progress != last_progress:
                update_job_progress(job_id, stage, progress)
                last_progress = progress
    return results


def run_analysis_job(job, heartbeat=None):
    job_id = job["id"]
    original_filename = job["original_filename"]
    file_path = UPLOAD_DIR / job["saved_filename"]
    frame_result = {}
    start_time = time.time()
    completed = False

    try:
        _check_cancelled(job_id, heartbeat)
        update_job_progress(job_id, "video_info", 10)
        video_info = get_video_info(str(file_path))
        if video_info.get("error"):
            raise ValueError(video_info["error"])

        update_job_progress(job_id, "extracting_frames", 15)
        frame_result = extract_frames(str(file_path), interval_sec=1, output_id=job_id)
        if frame_result.get("error") or not frame_result.get("frames"):
            raise ValueError(frame_result.get("error") or "no frames extracted")

        frame_paths = frame_result["frames"]
        pose_results = _analyze_frames(
            job_id,
            frame_paths,
            create_pose_landmarker,
            analyze_pose_from_frame,
            "analyzing_pose",
            20,
            42,
            heartbeat,
        )
        face_results = _analyze_frames(
            job_id,
            frame_paths,
            create_face_landmarker,
            analyze_face_from_frame,
            "analyzing_face",
            43,
            64,
            heartbeat,
        )

        _check_cancelled(job_id, heartbeat)
        update_job_progress(job_id, "analyzing_timeline", 68)
        timeline_result = analyze_timeline_scores(video_info, frame_result, pose_results, face_results)

        _check_cancelled(job_id, heartbeat)
        update_job_progress(job_id, "analyzing_audio", 74)
        audio_result = analyze_audio_from_video(str(file_path))
        filler_result = analyze_filler_words(
            audio_result.get("text", ""),
            audio_result.get("duration_seconds", 0),
        )
        audio_result.update(filler_result)

        _check_cancelled(job_id, heartbeat)
        update_job_progress(job_id, "calculating_scores", 86)
        gesture_result = analyze_gesture_from_pose_results(
            pose_results,
            video_info.get("duration_seconds", 0),
        )
        volume_result = analyze_volume_from_video(str(file_path))
        score_result = calculate_basic_score(
            video_info,
            frame_result,
            pose_results,
            face_results,
            audio_result,
            gesture_result,
            volume_result,
        )
        feedback_result = generate_feedback(score_result)
        processing_time = round(time.time() - start_time, 2)

        summary_result = {
            "status": "COMPLETED",
            "original_filename": original_filename,
            "total_score": score_result.get("total_score"),
            "summary_feedback": feedback_result.get("summary"),
            "processing_time_seconds": processing_time,
            "timeline_count": timeline_result.get("timeline_count"),
            "analysis_algorithm_version": ANALYSIS_ALGORITHM_VERSION,
            "ollama_model": OLLAMA_MODEL,
            "metrics": {
                "pose_detection_rate": score_result.get("pose_detection_rate"),
                "face_detection_rate": score_result.get("face_detection_rate"),
                "analysis_confidence": score_result.get("analysis_confidence"),
                "shoulder_balance_score": score_result.get("shoulder_balance_score"),
                "gaze_score": score_result.get("gaze_score"),
                "head_direction_score": score_result.get("head_direction_score"),
                "head_direction_valid_frames": score_result.get("head_direction_valid_frames"),
                "speech_speed_score": score_result.get("speech_speed_score"),
                "silence_score": score_result.get("silence_score"),
                "filler_score": score_result.get("filler_score"),
                "gesture_score": score_result.get("gesture_score"),
                "volume_score": score_result.get("volume_score"),
                "mean_volume_db": score_result.get("mean_volume_db"),
                "max_volume_db": score_result.get("max_volume_db"),
                "volume_level": score_result.get("volume_level"),
            },
        }
        analysis_data = {
            "status": "COMPLETED",
            "original_filename": original_filename,
            "video_info": video_info,
            "frame_result": {"saved_count": frame_result.get("saved_count"), "output_dir": None, "frames": []},
            "pose_results": pose_results,
            "face_results": face_results,
            "timeline_result": timeline_result,
            "audio_result": audio_result,
            "filler_result": filler_result,
            "gesture_result": gesture_result,
            "volume_result": volume_result,
            "score_result": score_result,
            "feedback_result": feedback_result,
            "summary_result": summary_result,
            "processing_time_seconds": processing_time,
            "analysis_metadata": {
                "algorithm_version": ANALYSIS_ALGORITHM_VERSION,
                "speech_speed_basis": audio_result.get("speech_speed_basis"),
                "ollama_model": OLLAMA_MODEL,
            },
        }

        _check_cancelled(job_id, heartbeat)
        update_job_progress(job_id, "saving_result", 96)
        save_analysis_result(analysis_data, result_id=job_id)
        result_file = RESULT_DIR / f"{job_id}.json"
        result_path = str(result_file)
        completed = mark_job_completed(
            job_id,
            summary_result,
            result_path=result_path,
            result_size_bytes=result_file.stat().st_size,
        )
        if not completed:
            delete_analysis_result(job_id)
            raise AnalysisCancelled()
    except AnalysisCancelled:
        delete_analysis_result(job_id)
        mark_job_cancelled(job_id)
    except Exception:
        logger.exception("Analysis job %s failed", safe_log_identifier(job_id))
        mark_job_failed(job_id, PUBLIC_ANALYSIS_ERROR, round(time.time() - start_time, 2))
    finally:
        safe_remove_directory(FRAME_DIR / job_id, FRAME_DIR)
        if completed and ensure_file_removed(file_path):
            clear_source_file(job_id)
