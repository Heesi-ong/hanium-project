import time
import unittest
from unittest.mock import patch

from Back.app.services import readiness
from Back.app.workers.analysis_worker import AnalysisWorkerManager


class ReadinessTest(unittest.TestCase):
    @patch.object(readiness, "get_disk_status", return_value={"ok": True})
    @patch.object(readiness, "get_ollama_status", return_value={"ok": True})
    @patch.object(readiness.analysis_worker_manager, "status", return_value={"running": True})
    @patch.object(readiness, "get_analysis_queue_status", return_value={"queued": 1, "processing": 0, "failed": 0})
    @patch.object(readiness, "ping_database")
    def test_all_dependencies_ready(self, _ping, _queue, _worker, _ollama, _disk):
        result = readiness.get_readiness_status()

        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["checks"]["queue"]["queued"], 1)

    @patch.object(readiness, "get_disk_status", return_value={"ok": True})
    @patch.object(readiness, "get_ollama_status", return_value={"ok": True})
    @patch.object(readiness.analysis_worker_manager, "status", return_value={"running": True})
    @patch.object(readiness, "ping_database", side_effect=RuntimeError("database down"))
    def test_database_failure_marks_service_not_ready(self, _ping, _worker, _ollama, _disk):
        result = readiness.get_readiness_status()

        self.assertEqual(result["status"], "not_ready")
        self.assertFalse(result["checks"]["database"]["ok"])

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


if __name__ == "__main__":
    unittest.main()
