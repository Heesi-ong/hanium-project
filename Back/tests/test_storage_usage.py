import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from Back.app.services import storage_usage


class StorageUsageTest(unittest.TestCase):
    def test_counts_owned_uploads_results_and_active_jobs(self):
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchall.return_value = [
            {"id": "job-1", "saved_filename": "source.mp4", "status": "PROCESSING"},
            {"id": "job-2", "saved_filename": "", "status": "COMPLETED"},
        ]
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            uploads = root / "uploads"
            results = root / "results"
            uploads.mkdir()
            results.mkdir()
            (uploads / "source.mp4").write_bytes(b"1234")
            (results / "job-2.json").write_bytes(b"12")
            with (
                patch.object(storage_usage, "UPLOAD_DIR", uploads),
                patch.object(storage_usage, "RESULT_DIR", results),
                patch.object(storage_usage, "get_connection", return_value=connection),
            ):
                result = storage_usage.get_user_storage_usage(7)

        self.assertEqual(result["used_bytes"], 6)
        self.assertEqual(result["active_analysis_count"], 1)
        connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
