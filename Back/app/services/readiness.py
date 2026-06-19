"""DB, 저장소, 모델 파일, 워커 상태를 종합해 readiness 응답을 만든다."""

import shutil

import requests

from ..config import MIN_FREE_DISK_MB, OLLAMA_BASE_URL, OLLAMA_HEALTH_TIMEOUT_SECONDS, OLLAMA_MODEL, UPLOAD_DIR
from ..services.analysis_jobs import get_analysis_queue_status
from ..services.database import ping_database
from ..services.runtime_storage import get_model_files_status, get_runtime_storage_status
from ..workers.analysis_worker import analysis_worker_manager


def get_disk_status():
    if not UPLOAD_DIR.is_dir():
        return {
            "ok": False,
            "error": "upload directory is missing",
            "path": str(UPLOAD_DIR),
            "minimum_free_mb": MIN_FREE_DISK_MB,
        }
    usage = shutil.disk_usage(UPLOAD_DIR)
    free_mb = round(usage.free / 1024 / 1024)
    return {
        "ok": free_mb >= MIN_FREE_DISK_MB,
        "path": str(UPLOAD_DIR),
        "free_mb": free_mb,
        "minimum_free_mb": MIN_FREE_DISK_MB,
    }


def get_ollama_status():
    try:
        response = requests.get(f"{OLLAMA_BASE_URL}/api/tags", timeout=OLLAMA_HEALTH_TIMEOUT_SECONDS)
        response.raise_for_status()
        models = {model.get("name") for model in response.json().get("models", [])}
        return {"ok": OLLAMA_MODEL in models, "configured_model": OLLAMA_MODEL, "model_available": OLLAMA_MODEL in models}
    except (requests.RequestException, ValueError):
        return {"ok": False, "configured_model": OLLAMA_MODEL, "model_available": False}


def get_readiness_status():
    checks = {}
    try:
        ping_database()
        checks["database"] = {"ok": True}
        checks["queue"] = {"ok": True, **get_analysis_queue_status()}
    except Exception as error:
        checks["database"] = {"ok": False, "error": type(error).__name__}
        checks["queue"] = {"ok": False}

    worker_status = analysis_worker_manager.status()
    checks["worker"] = {"ok": worker_status["running"], **worker_status}
    checks["storage"] = get_runtime_storage_status()
    checks["models"] = get_model_files_status()
    checks["ollama"] = get_ollama_status()
    checks["disk"] = get_disk_status()
    return {"status": "ready" if all(check["ok"] for check in checks.values()) else "not_ready", "checks": checks}
