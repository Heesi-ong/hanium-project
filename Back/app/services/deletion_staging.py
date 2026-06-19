"""파일 삭제 전 격리 영역으로 이동해 DB 삭제 실패 시 롤백할 수 있게 한다."""

import logging
import shutil
import time
from pathlib import Path
from uuid import uuid4

from ..config import BACK_DIR, DELETION_STAGING_DIR
from .log_safety import safe_log_path

logger = logging.getLogger(__name__)
COMMITTED_MARKER = ".committed"


class StagedDeletion:
    def __init__(self, paths):
        self.directory = DELETION_STAGING_DIR / str(uuid4())
        self.moves = []
        try:
            for index, source in enumerate(paths):
                self._stage_path(source, index)
        except Exception:
            self.rollback()
            raise

    def _stage_path(self, source, index):
        if not source:
            return
        path = Path(source).resolve()
        back_dir = BACK_DIR.resolve()
        if path == back_dir or back_dir not in path.parents:
            raise ValueError(f"삭제 격리 허용 범위를 벗어난 경로입니다: {path}")
        if not path.exists():
            return
        self.directory.mkdir(parents=True, exist_ok=True)
        destination = self.directory / f"{index:04d}-{path.name}"
        path.replace(destination)
        self.moves.append((path, destination))

    def rollback(self):
        restored = True
        for source, destination in reversed(self.moves):
            try:
                source.parent.mkdir(parents=True, exist_ok=True)
                destination.replace(source)
            except OSError:
                logger.exception("Failed to restore staged deletion path: %s", safe_log_path(source))
                restored = False
        if self.directory.exists():
            try:
                self.directory.rmdir()
            except OSError:
                restored = False
        return restored

    def purge(self):
        if not self.directory.exists():
            return True
        try:
            shutil.rmtree(self.directory)
            return True
        except OSError:
            logger.exception("Failed to purge staged deletion directory: %s", safe_log_path(self.directory))
            return False

    def commit(self):
        if self.directory.exists():
            (self.directory / COMMITTED_MARKER).touch()


def cleanup_staged_deletions(min_age_seconds=86400, now=None):
    if not DELETION_STAGING_DIR.exists():
        return 0
    current_time = time.time() if now is None else now
    deleted = 0
    for path in DELETION_STAGING_DIR.iterdir():
        if (
            not path.is_dir()
            or not (path / COMMITTED_MARKER).exists()
            or current_time - path.stat().st_mtime < min_age_seconds
        ):
            continue
        try:
            shutil.rmtree(path)
            deleted += 1
        except OSError:
            logger.exception("Failed to clean old staged deletion directory: %s", safe_log_path(path))
    return deleted
