import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from Back.app.services import result_saver


class ResultSaverTest(unittest.TestCase):
    def test_atomic_save_and_load_round_trip(self):
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(result_saver, "RESULT_DIR", Path(temp_dir)):
            result_saver.save_analysis_result({"status": "COMPLETED"}, result_id="result-1")

            result = result_saver.load_analysis_result("result-1")

        self.assertEqual(result["data"]["status"], "COMPLETED")

    def test_corrupt_result_is_treated_as_missing(self):
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(result_saver, "RESULT_DIR", Path(temp_dir)):
            (Path(temp_dir) / "result-1.json").write_text("{broken", encoding="utf-8")

            result = result_saver.load_analysis_result("result-1")

        self.assertIsNone(result)


if __name__ == "__main__":
    unittest.main()
