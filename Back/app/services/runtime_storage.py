"""업로드, 프레임, 결과, 모델 등 런타임 저장소 디렉터리와 모델 파일 상태를 확인한다."""

from ..config import (
    AI_COACHING_DIR,
    DELETION_STAGING_DIR,
    FRAME_DIR,
    MODEL_DIR,
    PRACTICE_CONTEXT_DIR,
    RESULT_DIR,
    STORAGE_DIR,
    UPLOAD_DIR,
)

RUNTIME_STORAGE_DIRS = (
    STORAGE_DIR,
    UPLOAD_DIR,
    FRAME_DIR,
    RESULT_DIR,
    PRACTICE_CONTEXT_DIR,
    AI_COACHING_DIR,
    DELETION_STAGING_DIR,
)

REQUIRED_MODEL_FILES = (
    MODEL_DIR / "face_landmarker.task",
    MODEL_DIR / "pose_landmarker.task",
)


def ensure_runtime_storage_dirs():
    for path in RUNTIME_STORAGE_DIRS:
        path.mkdir(parents=True, exist_ok=True)


def get_runtime_storage_status():
    missing = [str(path) for path in RUNTIME_STORAGE_DIRS if not path.is_dir()]
    return {
        "ok": not missing,
        "missing": missing,
    }


def get_model_files_status():
    files = [
        {
            "path": str(path),
            "exists": path.is_file(),
        }
        for path in REQUIRED_MODEL_FILES
    ]
    missing = [file["path"] for file in files if not file["exists"]]
    return {
        "ok": not missing,
        "files": files,
        "missing": missing,
    }
