import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from Back.app.workers import analysis_worker


class AnalysisWorkerCleanupTest(unittest.TestCase):
    @patch.object(analysis_worker.threading, "Thread")
    def test_worker_manager_start_is_idempotent(self, thread):
        created_thread = MagicMock()
        thread.return_value = created_thread
        manager = analysis_worker.AnalysisWorkerManager()

        manager.start()
        manager.start()

        self.assertEqual(thread.call_count, analysis_worker.ANALYSIS_WORKERS + 1)

    def test_cleanup_keeps_processing_job_and_removes_orphan(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            frame_root = Path(temp_dir)
            active = frame_root / "active-job"
            orphan = frame_root / "orphan-job"
            for path in (active, orphan):
                path.mkdir()
                (path / "frame.jpg").write_bytes(b"frame")
                old_time = time.time() - 3600
                os.utime(path, (old_time, old_time))

            with (
                patch.object(analysis_worker, "FRAME_DIR", frame_root),
                patch.object(analysis_worker, "ORPHAN_FRAME_MIN_AGE_MINUTES", 1),
                patch.object(analysis_worker, "list_processing_job_ids", return_value={"active-job"}),
            ):
                result = analysis_worker.cleanup_orphan_frames()

            self.assertEqual(result["deleted_directory_count"], 1)
            self.assertTrue(active.exists())
            self.assertFalse(orphan.exists())

    @patch.object(analysis_worker, "list_expired_result_ids", return_value=[])
    def test_completed_result_cleanup_is_disabled_by_default(self, _list_expired):
        self.assertEqual(analysis_worker.cleanup_expired_results(), 0)

    @patch.object(analysis_worker, "StagedDeletion")
    @patch.object(analysis_worker, "delete_result_records")
    @patch.object(analysis_worker, "list_expired_result_ids", return_value=["old-result"])
    def test_expired_completed_result_removes_db_and_json(
        self, _list_expired, delete_records, staged_deletion
    ):
        self.assertEqual(analysis_worker.cleanup_expired_results(), 1)
        delete_records.assert_called_once_with("old-result")
        staged_deletion.return_value.commit.assert_called_once_with()
        staged_deletion.return_value.purge.assert_called_once_with()

    @patch.object(analysis_worker, "clear_source_file")
    @patch.object(analysis_worker, "ensure_file_removed", return_value=False)
    @patch.object(
        analysis_worker,
        "list_expired_source_files",
        return_value=[{"id": "job-1", "saved_filename": "source.mp4"}],
    )
    def test_expired_source_reference_is_retained_when_file_deletion_fails(
        self, _list_expired, _remove_file, clear_source
    ):
        result = analysis_worker.cleanup_expired_sources()

        self.assertEqual(result, {"deleted": 0, "failed": 1})
        clear_source.assert_not_called()


if __name__ == "__main__":
    unittest.main()
