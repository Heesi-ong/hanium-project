"""DB 작업 큐를 폴링해 발표 분석 파이프라인을 실행하고 유지보수 작업 상태를 관리한다."""

import logging
import threading
import time

from ..config import (
    AI_COACHING_DIR,
    ANALYSIS_POLL_SECONDS,
    ANALYSIS_WORKERS,
    FRAME_DIR,
    MAINTENANCE_INTERVAL_SECONDS,
    MAINTENANCE_STALE_SECONDS,
    ORPHAN_FRAME_MIN_AGE_MINUTES,
    RESULT_DIR,
    UPLOAD_DIR,
    WORKER_HEARTBEAT_STALE_SECONDS,
)
from ..repositories.analysis_job_repository import list_processing_job_ids
from ..services.admin_audit import delete_expired_admin_audit_logs
from ..services.analysis_jobs import (
    claim_next_job,
    clear_source_file,
    list_all_job_ids,
    list_expired_result_ids,
    list_expired_source_files,
)
from ..services.analysis_pipeline import run_analysis_job
from ..services.auth_service import delete_expired_sessions
from ..services.conversation_contexts import delete_result_records
from ..services.deletion_staging import StagedDeletion, cleanup_staged_deletions
from ..services.file_cleaner import cleanup_orphan_directories, ensure_file_removed
from ..services.log_safety import safe_log_identifier
from ..services.practice_coaching import delete_practice_context, list_orphan_practice_contexts
from ..services.practice_contexts import PRACTICE_CONTEXT_DIR

logger = logging.getLogger(__name__)


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
        staged = None
        try:
            staged = StagedDeletion(
                [
                    RESULT_DIR / f"{job_id}.json",
                    FRAME_DIR / job_id,
                    PRACTICE_CONTEXT_DIR / f"{job_id}.json",
                    AI_COACHING_DIR / f"{job_id}.json",
                ]
            )
            delete_result_records(job_id)
        except Exception:
            if staged is not None:
                staged.rollback()
            logger.exception("Failed to safely delete expired result: %s", safe_log_identifier(job_id))
        else:
            staged.commit()
            staged.purge()
            deleted += 1
    return deleted


def cleanup_orphan_practice_contexts():
    orphan_ids = list_orphan_practice_contexts(list_all_job_ids())
    deleted = sum(delete_practice_context(result_id) for result_id in orphan_ids)
    return {"orphan_count": len(orphan_ids), "deleted": deleted}


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
        self._last_maintenance_heartbeat = None
        self._last_maintenance_at = None
        self._last_maintenance_error = None
        self._last_maintenance_task = None
        self._maintenance_tasks = {}

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
                    run_analysis_job(job, heartbeat=self.touch_worker_heartbeat)
                    continue
            except Exception:
                logger.exception("Analysis worker loop failed")
            self._stop_event.wait(ANALYSIS_POLL_SECONDS)

    def _maintenance_loop(self):
        while not self._stop_event.is_set():
            self.touch_maintenance_heartbeat()
            failed_errors = []
            for name, task in (
                ("cleanup_expired_sources", cleanup_expired_sources),
                ("cleanup_expired_results", cleanup_expired_results),
                ("cleanup_orphan_frames", cleanup_orphan_frames),
                ("cleanup_orphan_practice_contexts", cleanup_orphan_practice_contexts),
                ("cleanup_staged_deletions", cleanup_staged_deletions),
                ("delete_expired_sessions", delete_expired_sessions),
                ("delete_expired_admin_audit_logs", delete_expired_admin_audit_logs),
            ):
                error = self._run_maintenance_task(name, task)
                if error:
                    failed_errors.append(f"{name}: {error}")
            if failed_errors:
                self._last_maintenance_error = "; ".join(failed_errors)
            else:
                self._last_maintenance_at = time.time()
                self._last_maintenance_error = None
            self._wait_for_maintenance_interval()

    def _run_maintenance_task(self, name, task):
        started_at = time.time()
        self._last_maintenance_task = name
        self.touch_maintenance_heartbeat()
        state = self._maintenance_tasks.setdefault(name, {})
        state.update(
            {
                "running": True,
                "last_started_at_epoch": started_at,
                "last_finished_at_epoch": state.get("last_finished_at_epoch"),
                "last_success_at_epoch": state.get("last_success_at_epoch"),
                "last_error": None,
            }
        )
        try:
            task()
        except Exception as error:
            finished_at = time.time()
            state.update(
                {
                    "running": False,
                    "last_finished_at_epoch": finished_at,
                    "last_error_at_epoch": finished_at,
                    "last_error": str(error),
                }
            )
            self.touch_maintenance_heartbeat()
            logger.exception("Analysis maintenance task failed: %s", name)
            return str(error)
        finished_at = time.time()
        state.update(
            {
                "running": False,
                "last_finished_at_epoch": finished_at,
                "last_success_at_epoch": finished_at,
                "last_error": None,
            }
        )
        self.touch_maintenance_heartbeat()
        return None

    def _wait_for_maintenance_interval(self):
        deadline = time.time() + MAINTENANCE_INTERVAL_SECONDS
        while not self._stop_event.is_set():
            remaining = deadline - time.time()
            if remaining <= 0:
                return
            self.touch_maintenance_heartbeat()
            self._stop_event.wait(min(60, remaining))

    def status(self):
        worker_threads = list(self._threads)
        maintenance_alive = bool(self._maintenance_thread and self._maintenance_thread.is_alive())
        now = time.time()
        worker_heartbeat_age = now - self._last_worker_heartbeat if self._last_worker_heartbeat else None
        maintenance_heartbeat_age = (
            now - self._last_maintenance_heartbeat if self._last_maintenance_heartbeat else None
        )
        maintenance_age = now - self._last_maintenance_at if self._last_maintenance_at else None
        worker_heartbeat_stale = worker_heartbeat_age is None or worker_heartbeat_age > WORKER_HEARTBEAT_STALE_SECONDS
        maintenance_stale = (
            maintenance_heartbeat_age is None or maintenance_heartbeat_age > MAINTENANCE_STALE_SECONDS
        )
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
            "last_maintenance_heartbeat_epoch": self._last_maintenance_heartbeat,
            "last_maintenance_at_epoch": self._last_maintenance_at,
            "last_maintenance_error": self._last_maintenance_error,
            "last_maintenance_task": self._last_maintenance_task,
            "maintenance_tasks": self._maintenance_tasks,
            "worker_heartbeat_age_seconds": worker_heartbeat_age,
            "worker_heartbeat_stale": worker_heartbeat_stale,
            "maintenance_heartbeat_age_seconds": maintenance_heartbeat_age,
            "maintenance_age_seconds": maintenance_age,
            "maintenance_stale": maintenance_stale,
        }

    def touch_worker_heartbeat(self):
        self._last_worker_heartbeat = time.time()

    def touch_maintenance_heartbeat(self):
        self._last_maintenance_heartbeat = time.time()


analysis_worker_manager = AnalysisWorkerManager()
