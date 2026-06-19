import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[2] / "scripts" / "check-deletion-staging.py"
SPEC = importlib.util.spec_from_file_location("check_deletion_staging", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DeletionStagingCliTest(unittest.TestCase):
    def test_inspection_marks_uncommitted_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            operation = root / "operation-1"
            operation.mkdir()
            (operation / "file.json").write_text("data", encoding="utf-8")

            results = MODULE.inspect_staging(root, now=operation.stat().st_mtime + 10)

        self.assertEqual(results[0]["committed"], False)
        self.assertEqual(results[0]["entry_count"], 1)


if __name__ == "__main__":
    unittest.main()
