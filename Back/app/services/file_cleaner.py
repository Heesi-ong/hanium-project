"""백엔드 저장소 내부 파일과 오래된 임시 디렉터리를 안전하게 삭제한다."""

import logging
import os
import shutil
import time
from pathlib import Path

from .log_safety import safe_log_path

logger = logging.getLogger(__name__)


def safe_remove_file(file_path: str):
    if file_path and os.path.exists(file_path):
        try:
            os.remove(file_path)
            return True
        except Exception:
            logger.exception("Failed to remove file: %s", safe_log_path(file_path))
            return False

    return False


def ensure_file_removed(file_path: str | Path):
    if not file_path:
        return True
    return not os.path.exists(file_path) or safe_remove_file(file_path)


def safe_remove_empty_dir(dir_path: str):
    if not dir_path:
        return False

    if not os.path.exists(dir_path):
        return False

    if not os.path.isdir(dir_path):
        return False

    try:
        if len(os.listdir(dir_path)) == 0:
            os.rmdir(dir_path)
            return True
    except Exception:
        logger.exception("Failed to remove empty directory: %s", safe_log_path(dir_path))
        return False

    return False


def safe_remove_files(file_paths: list):
    deleted_file_count = 0
    deleted_empty_dir_count = 0
    target_dirs = set()

    for file_path in file_paths:
        if file_path:
            target_dirs.add(os.path.dirname(file_path))

        if safe_remove_file(file_path):
            deleted_file_count += 1

    for dir_path in target_dirs:
        if safe_remove_empty_dir(dir_path):
            deleted_empty_dir_count += 1

    return {
        "deleted_file_count": deleted_file_count,
        "deleted_empty_dir_count": deleted_empty_dir_count
    }


def safe_remove_directory(dir_path: str | Path, allowed_root: str | Path):
    path = Path(dir_path).resolve()
    root = Path(allowed_root).resolve()

    if path == root or root not in path.parents or not path.is_dir():
        return False

    try:
        shutil.rmtree(path)
        return True
    except Exception:
        logger.exception("Failed to remove directory: %s", safe_log_path(path))
        return False


def cleanup_orphan_directories(root_dir, active_directory_names=(), min_age_seconds=600, now=None):
    root = Path(root_dir)
    if not root.exists():
        return {"deleted_directory_count": 0, "kept_directory_count": 0}

    active_names = set(active_directory_names)
    current_time = time.time() if now is None else now
    deleted = 0
    kept = 0

    for path in root.iterdir():
        if not path.is_dir():
            continue
        age_seconds = current_time - path.stat().st_mtime
        if path.name in active_names or age_seconds < min_age_seconds:
            kept += 1
            continue
        if safe_remove_directory(path, root):
            deleted += 1
        else:
            kept += 1

    return {"deleted_directory_count": deleted, "kept_directory_count": kept}
