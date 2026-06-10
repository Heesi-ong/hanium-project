import os
import tempfile
import time
import unittest
from pathlib import Path

from Back.app.services.file_cleaner import (
    cleanup_orphan_directories,
    ensure_file_removed,
    safe_remove_directory,
    safe_remove_files,
)


class FileCleanerTest(unittest.TestCase):
    def test_removes_files_and_empty_parent_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            frame_dir = Path(temp_dir) / "frames"
            frame_dir.mkdir()
            frame_path = frame_dir / "frame.jpg"
            frame_path.write_bytes(b"frame")

            result = safe_remove_files([frame_path])

            self.assertEqual(result["deleted_file_count"], 1)
            self.assertEqual(result["deleted_empty_dir_count"], 1)
            self.assertFalse(frame_dir.exists())

    def test_removes_only_old_inactive_frame_directories(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "frames"
            active = root / "active-job"
            recent = root / "recent-job"
            orphan = root / "orphan-job"
            for path in (active, recent, orphan):
                path.mkdir(parents=True)
                (path / "frame.jpg").write_bytes(b"frame")

            now = time.time()
            old_time = now - 3600
            for path in (active, orphan):
                os.utime(path, (old_time, old_time))

            result = cleanup_orphan_directories(
                root,
                active_directory_names={"active-job"},
                min_age_seconds=600,
                now=now,
            )

            self.assertEqual(result["deleted_directory_count"], 1)
            self.assertTrue(active.exists())
            self.assertTrue(recent.exists())
            self.assertFalse(orphan.exists())

    def test_directory_removal_is_restricted_to_allowed_root(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "frames"
            root.mkdir()

            self.assertFalse(safe_remove_directory(root, root))
            self.assertTrue(root.exists())

    def test_ensure_file_removed_accepts_already_missing_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            self.assertTrue(ensure_file_removed(Path(temp_dir) / "missing.json"))


if __name__ == "__main__":
    unittest.main()
