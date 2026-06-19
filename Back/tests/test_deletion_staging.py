import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from Back.app.services import deletion_staging


class DeletionStagingTest(unittest.TestCase):
    def test_staged_files_can_be_rolled_back(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "results" / "result.json"
            source.parent.mkdir()
            source.write_text("result", encoding="utf-8")
            with (
                patch.object(deletion_staging, "BACK_DIR", root),
                patch.object(deletion_staging, "DELETION_STAGING_DIR", root / ".deletion_staging"),
            ):
                staged = deletion_staging.StagedDeletion([source])
                self.assertFalse(source.exists())
                self.assertTrue(staged.rollback())
            self.assertEqual(source.read_text(encoding="utf-8"), "result")

    def test_staged_files_are_removed_only_when_purged(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "uploads" / "video.mp4"
            source.parent.mkdir()
            source.write_bytes(b"video")
            with (
                patch.object(deletion_staging, "BACK_DIR", root),
                patch.object(deletion_staging, "DELETION_STAGING_DIR", root / ".deletion_staging"),
            ):
                staged = deletion_staging.StagedDeletion([source])
                staging_directory = staged.directory
                self.assertTrue(staging_directory.exists())
                staged.commit()
                self.assertTrue(staged.purge())
            self.assertFalse(source.exists())
            self.assertFalse(staging_directory.exists())

    def test_paths_outside_backend_directory_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir, tempfile.TemporaryDirectory() as outside_dir:
            root = Path(temp_dir)
            outside = Path(outside_dir) / "outside.json"
            outside.write_text("data", encoding="utf-8")
            with (
                patch.object(deletion_staging, "BACK_DIR", root),
                patch.object(deletion_staging, "DELETION_STAGING_DIR", root / ".deletion_staging"),
            ):
                with self.assertRaises(ValueError):
                    deletion_staging.StagedDeletion([outside])
            self.assertTrue(outside.exists())

    def test_old_staged_deletions_are_cleaned(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            old = root / ".deletion_staging" / "old-operation"
            old.mkdir(parents=True)
            (old / "file.json").write_text("data", encoding="utf-8")
            (old / deletion_staging.COMMITTED_MARKER).touch()
            with patch.object(deletion_staging, "DELETION_STAGING_DIR", root / ".deletion_staging"):
                deleted = deletion_staging.cleanup_staged_deletions(
                    min_age_seconds=60,
                    now=old.stat().st_mtime + 61,
                )
            self.assertEqual(deleted, 1)
            self.assertFalse(old.exists())

    def test_uncommitted_staged_deletion_is_preserved_for_manual_recovery(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            staged = root / ".deletion_staging" / "rollback-failed"
            staged.mkdir(parents=True)
            (staged / "file.json").write_text("data", encoding="utf-8")
            with patch.object(deletion_staging, "DELETION_STAGING_DIR", root / ".deletion_staging"):
                deleted = deletion_staging.cleanup_staged_deletions(
                    min_age_seconds=60,
                    now=staged.stat().st_mtime + 61,
                )
            self.assertEqual(deleted, 0)
            self.assertTrue(staged.exists())


if __name__ == "__main__":
    unittest.main()
