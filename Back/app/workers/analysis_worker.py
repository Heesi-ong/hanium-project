import logging
import threading
import time

from ..config import (
    ANALYSIS_ALGORITHM_VERSION,
    ANALYSIS_POLL_SECONDS,
    ANALYSIS_WORKERS,
    FRAME_DIR,
    MAINTENANCE_INTERVAL_SECONDS,
    MAINTENANCE_STALE_SECONDS,
    OLLAMA_MODEL,
    ORPHAN_FRAME_MIN_AGE_MINUTES,
    RESULT_DIR,
    UPLOAD_DIR,
    WORKER_HEARTBEAT_STALE_SECONDS,
)
from ..repositories.analysis_job_repository import list_processing_job_ids
from ..services.analysis_jobs import (
    claim_next_job,
    clear_source_file,
    delete_completed_job,
    is_cancel_requested,
    list_expired_result_ids,
    list_expired_source_files,
    mark_job_cancelled,
    mark_job_completed,
    mark_job_failed,
    update_job_progress,
)
from ..services.audio_analyzer import analyze_audio_from_video
from ..services.auth_service import delete_expired_sessions
from ..services.face_analyzer import analyze_face_from_frame, create_face_landmarker
from ..services.feedback_generator import generate_feedback
from ..services.file_cleaner import cleanup_orphan_directories, ensure_file_removed, safe_remove_directory
from ..services.filler_analyzer import analyze_filler_words
from ..services.frame_extractor import extract_frames
from ..services.gesture_analyzer import analyze_gesture_from_pose_results
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


def _check_cancelled(job_id):
    analysis_worker_manager.touch_worker_heartbeat()
    if is_cancel_requested(job_id):
        raise AnalysisCancelled()


def _analyze_frames(job_id, frame_paths, analyzer_factory, analyzer, stage, start, end):
    results = []
    total = max(1, len(frame_paths))
    last_progress = None
    with analyzer_factory() as landmarker:
        for index, frame_path in enumerate(frame_paths):
            _check_cancelled(job_id)
            results.append(analyzer(frame_path, landmarker))
            progress = start + int(((index + 1) / total) * (end - start))
            if progress != last_progress:
                update_job_progress(job_id, stage, progress)
                last_progress = progress
    return results


def run_analysis_job(job):
    job_id = job["id"]
    original_filename = job["original_filename"]
    file_path = UPLOAD_DIR / job["saved_filename"]
    frame_result = {}
    start_time = time.time()
    completed = False

    try:
        _check_cancelled(job_id)
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
            job_id, frame_paths, create_pose_landmarker, analyze_pose_from_frame, "analyzing_pose", 20, 42
        )
        face_results = _analyze_frames(
            job_id, frame_paths, create_face_landmarker, analyze_face_from_frame, "analyzing_face", 43, 64
        )

        _check_cancelled(job_id)
        update_job_progress(job_id, "analyzing_timeline", 68)
        timeline_result = analyze_timeline_scores(video_info, frame_result, pose_results, face_results)

        _check_cancelled(job_id)
        update_job_progress(job_id, "analyzing_audio", 74)
        audio_result = analyze_audio_from_video(str(file_path))
        filler_result = analyze_filler_words(audio_result.get("text", ""))
        audio_result.update(filler_result)

        _check_cancelled(job_id)
        update_job_progress(job_id, "calculating_scores", 86)
        gesture_result = analyze_gesture_from_pose_results(pose_results)
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
                "shoulder_balance_score": score_result.get("shoulder_balance_score"),
                "gaze_score": score_result.get("gaze_score"),
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
                "ollama_model": OLLAMA_MODEL,
            },
        }

        _check_cancelled(job_id)
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
        logger.exception("Analysis job %s failed", job_id)
        mark_job_failed(job_id, PUBLIC_ANALYSIS_ERROR, round(time.time() - start_time, 2))
    finally:
        safe_remove_directory(FRAME_DIR / job_id, FRAME_DIR)
        if completed and ensure_file_removed(file_path):
            clear_source_file(job_id)


def cleanup_expired_sources():
    deleted = 0
    failed = 0
    for job in list_expired_source_files():
        if ensure_file_removed(UPLOAD_DIR / job["saved_filename"]):
            clear_source_file(job["id"])
            deleted += 1
        else:
            failed += 1
    if failed:
        logger.warning("Failed to remove %s expired source files; database references retained", failed)
    return {"deleted": deleted, "failed": failed}


def cleanup_expired_results():
    deleted = 0
    for job_id in list_expired_result_ids():
        delete_analysis_result(job_id)
        if ensure_file_removed(RESULT_DIR / f"{job_id}.json") and delete_completed_job(job_id):
            deleted += 1
    return deleted


def cleanup_orphan_frames():
    result = cleanup_orphan_directories(
        FRAME_DIR,
        active_directory_names=list_processing_job_ids(),
        min_age_seconds=ORPHAN_FRAME_MIN_AGE_MINUTES * 60,
    )
    if result["deleted_directory_count"]:
        logger.info("Removed orphan frame directories: %s", result)
    return result


class AnalysisWorkerManager:
    def __init__(self):
        self._stop_event = threading.Event()
        self._threads = []
        self._maintenance_thread = None
        self._started_at = None
        self._last_worker_heartbeat = None
        self._last_maintenance_at = None
        self._last_maintenance_error = None

    def start(self):
        if self._threads:
            return
        self._stop_event.clear()
        self._started_at = time.time()
        for index in range(ANALYSIS_WORKERS):
            thread = threading.Thread(
                target=self._loop,
                name=f"analysis-worker-{index + 1}",
                daemon=True,
            )
            thread.start()
            self._threads.append(thread)
        self._maintenance_thread = threading.Thread(
            target=self._maintenance_loop,
            name="analysis-maintenance",
            daemon=True,
        )
        self._maintenance_thread.start()

    def stop(self):
        self._stop_event.set()
        for thread in self._threads:
            thread.join(timeout=5)
        if self._maintenance_thread:
            self._maintenance_thread.join(timeout=5)
        self._threads.clear()
        self._maintenance_thread = None

    def _loop(self):
        while not self._stop_event.is_set():
            self.touch_worker_heartbeat()
            try:
                job = claim_next_job()
                if job:
                    run_analysis_job(job)
                    continue
            except Exception:
                logger.exception("Analysis worker loop failed")
            self._stop_event.wait(ANALYSIS_POLL_SECONDS)

    def _maintenance_loop(self):
        while not self._stop_event.is_set():
            try:
                cleanup_expired_sources()
                cleanup_expired_results()
                cleanup_orphan_frames()
                delete_expired_sessions()
                self._last_maintenance_at = time.time()
                self._last_maintenance_error = None
            except Exception as error:
                self._last_maintenance_error = str(error)
                logger.exception("Analysis maintenance loop failed")
            self._stop_event.wait(MAINTENANCE_INTERVAL_SECONDS)

    def status(self):
        worker_threads = list(self._threads)
        maintenance_alive = bool(self._maintenance_thread and self._maintenance_thread.is_alive())
        now = time.time()
        worker_heartbeat_age = now - self._last_worker_heartbeat if self._last_worker_heartbeat else None
        maintenance_age = now - self._last_maintenance_at if self._last_maintenance_at else None
        worker_heartbeat_stale = worker_heartbeat_age is None or worker_heartbeat_age > WORKER_HEARTBEAT_STALE_SECONDS
        maintenance_stale = maintenance_age is None or maintenance_age > MAINTENANCE_STALE_SECONDS
        running = (
            bool(worker_threads)
            and all(thread.is_alive() for thread in worker_threads)
            and maintenance_alive
            and not worker_heartbeat_stale
            and not maintenance_stale
            and self._last_maintenance_error is None
        )
        return {
            "running": running,
            "worker_count": len(worker_threads),
            "active_worker_count": sum(thread.is_alive() for thread in worker_threads),
            "maintenance_running": maintenance_alive,
            "started_at_epoch": self._started_at,
            "last_worker_heartbeat_epoch": self._last_worker_heartbeat,
            "last_maintenance_at_epoch": self._last_maintenance_at,
            "last_maintenance_error": self._last_maintenance_error,
            "worker_heartbeat_age_seconds": worker_heartbeat_age,
            "worker_heartbeat_stale": worker_heartbeat_stale,
            "maintenance_age_seconds": maintenance_age,
            "maintenance_stale": maintenance_stale,
        }

    def touch_worker_heartbeat(self):
        self._last_worker_heartbeat = time.time()


analysis_worker_manager = AnalysisWorkerManager()
