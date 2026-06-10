import unittest
from unittest.mock import patch

from Back.app.services import readiness


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


if __name__ == "__main__":
    unittest.main()
