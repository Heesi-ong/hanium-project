import time
import unittest
from pathlib import Path
from unittest.mock import patch

from Back.app.services import readiness
from Back.app.workers.analysis_worker import AnalysisWorkerManager


class ReadinessTest(unittest.TestCase):
    @patch.object(readiness, "get_model_files_status", return_value={"ok": True})
    @patch.object(readiness, "get_runtime_storage_status", return_value={"ok": True})
    @patch.object(readiness, "get_disk_status", return_value={"ok": True})
    @patch.object(readiness, "get_ollama_status", return_value={"ok": True})
    @patch.object(readiness.analysis_worker_manager, "status", return_value={"running": True})
    @patch.object(readiness, "get_analysis_queue_status", return_value={"queued": 1, "processing": 0, "failed": 0})
    @patch.object(readiness, "ping_database")
    def test_all_dependencies_ready(self, _ping, _queue, _worker, _ollama, _disk, _storage, _models):
        result = readiness.get_readiness_status()

        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["checks"]["queue"]["queued"], 1)

    @patch.object(readiness, "get_model_files_status", return_value={"ok": True})
    @patch.object(readiness, "get_runtime_storage_status", return_value={"ok": True})
    @patch.object(readiness, "get_disk_status", return_value={"ok": True})
    @patch.object(readiness, "get_ollama_status", return_value={"ok": True})
    @patch.object(readiness.analysis_worker_manager, "status", return_value={"running": True})
    @patch.object(readiness, "ping_database", side_effect=RuntimeError("database down"))
    def test_database_failure_marks_service_not_ready(self, _ping, _worker, _ollama, _disk, _storage, _models):
        result = readiness.get_readiness_status()

        self.assertEqual(result["status"], "not_ready")
        self.assertFalse(result["checks"]["database"]["ok"])

    @patch.object(readiness, "UPLOAD_DIR", Path("/missing/hanium/upload-dir"))
    def test_missing_upload_directory_marks_disk_not_ready(self):
        result = readiness.get_disk_status()

        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "upload directory is missing")

    @patch.object(readiness, "get_model_files_status", return_value={"ok": False, "missing": ["missing.task"]})
    @patch.object(readiness, "get_runtime_storage_status", return_value={"ok": True})
    @patch.object(readiness, "get_disk_status", return_value={"ok": True})
    @patch.object(readiness, "get_ollama_status", return_value={"ok": True})
    @patch.object(readiness.analysis_worker_manager, "status", return_value={"running": True})
    @patch.object(readiness, "get_analysis_queue_status", return_value={"queued": 0, "processing": 0, "failed": 0})
    @patch.object(readiness, "ping_database")
    def test_missing_model_file_marks_service_not_ready(
        self,
        _ping,
        _queue,
        _worker,
        _ollama,
        _disk,
        _storage,
        _models,
    ):
        result = readiness.get_readiness_status()

        self.assertEqual(result["status"], "not_ready")
        self.assertFalse(result["checks"]["models"]["ok"])

    @patch("Back.app.workers.analysis_worker.WORKER_HEARTBEAT_STALE_SECONDS", 5)
    @patch("Back.app.workers.analysis_worker.MAINTENANCE_STALE_SECONDS", 5)
    def test_stale_worker_heartbeat_marks_manager_not_running(self):
        manager = AnalysisWorkerManager()
        alive_thread = unittest.mock.Mock()
        alive_thread.is_alive.return_value = True
        manager._threads = [alive_thread]
        manager._maintenance_thread = alive_thread
        manager._last_worker_heartbeat = time.time() - 10
        manager._last_maintenance_at = time.time()

        status = manager.status()

        self.assertFalse(status["running"])
        self.assertTrue(status["worker_heartbeat_stale"])

    def test_maintenance_task_status_records_failures_and_continues(self):
        manager = AnalysisWorkerManager()

        def fail():
            raise RuntimeError("cleanup failed")

        self.assertEqual(manager._run_maintenance_task("failed_task", fail), "cleanup failed")
        self.assertIsNone(manager._run_maintenance_task("next_task", lambda: None))

        status = manager.status()
        self.assertEqual(status["last_maintenance_task"], "next_task")
        self.assertEqual(status["maintenance_tasks"]["failed_task"]["last_error"], "cleanup failed")
        self.assertIsNone(status["maintenance_tasks"]["next_task"]["last_error"])


if __name__ == "__main__":
    unittest.main()
