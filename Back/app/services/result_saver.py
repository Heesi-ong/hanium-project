import json
import logging
import os
import tempfile
from datetime import datetime
from uuid import uuid4

from ..config import RESULT_DIR
from .file_cleaner import safe_remove_empty_dir, safe_remove_file

logger = logging.getLogger(__name__)


def save_analysis_result(data: dict, result_id=None):
    os.makedirs(RESULT_DIR, exist_ok=True)

    result_id = result_id or str(uuid4())
    filename = f"{result_id}.json"
    file_path = RESULT_DIR / filename

    result_data = {
        "result_id": result_id,
        "created_at": datetime.now().isoformat(),
        "data": data
    }

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=RESULT_DIR, delete=False) as file:
            temp_path = file.name
            json.dump(result_data, file, ensure_ascii=False, indent=2)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp_path, file_path)
    finally:
        if temp_path and os.path.exists(temp_path):
            safe_remove_file(temp_path)

    return {
        "result_id": result_id
    }


def load_analysis_result(result_id: str):
    file_path = RESULT_DIR / f"{result_id}.json"

    if not os.path.exists(file_path):
        return None

    try:
        with open(file_path, "r", encoding="utf-8") as file:
            return json.load(file)
    except (OSError, json.JSONDecodeError):
        logger.exception("Failed to load analysis result: %s", result_id)
        return None


def delete_analysis_result(result_id: str):
    file_path = RESULT_DIR / f"{result_id}.json"

    if not os.path.exists(file_path):
        return None

    try:
        with open(file_path, "r", encoding="utf-8") as file:
            result_file = json.load(file)
    except (OSError, json.JSONDecodeError):
        logger.exception("Failed to read analysis result before deletion: %s", result_id)
        result_file = {}

    data = result_file.get("data", {})

    deleted_upload_count = 0
    deleted_frame_count = 0
    deleted_result_count = 0
    deleted_empty_dir_count = 0

    saved_path = data.get("saved_path")
    if safe_remove_file(saved_path):
        deleted_upload_count += 1

    frame_result = data.get("frame_result", {})
    frame_paths = frame_result.get("frames", [])

    frame_dirs = set()

    for frame_path in frame_paths:
        if frame_path:
            frame_dirs.add(os.path.dirname(frame_path))

        if safe_remove_file(frame_path):
            deleted_frame_count += 1

    for frame_dir in frame_dirs:
        if safe_remove_empty_dir(frame_dir):
            deleted_empty_dir_count += 1

    if safe_remove_file(file_path):
        deleted_result_count += 1

    return {
        "result_id": result_id,
        "deleted_result_count": deleted_result_count,
        "deleted_upload_count": deleted_upload_count,
        "deleted_frame_count": deleted_frame_count,
        "deleted_empty_dir_count": deleted_empty_dir_count,
        "total_deleted_count": (
            deleted_result_count +
            deleted_upload_count +
            deleted_frame_count +
            deleted_empty_dir_count
        )
    }


def build_result_summary_item(result_file: dict, file_path: str):
    result_id = result_file.get("result_id")
    created_at = result_file.get("created_at")
    data = result_file.get("data", {})

    status = data.get("status")
    summary_result = data.get("summary_result", {})
    score_result = data.get("score_result", {})
    feedback_result = data.get("feedback_result", {})

    metrics = summary_result.get("metrics", {})

    if not metrics and status == "COMPLETED":
        metrics = {
            "pose_detection_rate": score_result.get("pose_detection_rate"),
            "face_detection_rate": score_result.get("face_detection_rate"),
            "shoulder_balance_score": score_result.get("shoulder_balance_score"),
            "gaze_score": score_result.get("gaze_score"),
            "speech_speed_score": score_result.get("speech_speed_score"),
            "silence_score": score_result.get("silence_score"),
            "filler_score": score_result.get("filler_score"),
            "gesture_score": score_result.get("gesture_score"),
            "volume_score": score_result.get("volume_score")
        }

    return {
        "result_id": result_id,
        "created_at": created_at,
        "status": status,
        "original_filename": data.get("original_filename"),
        "total_score": summary_result.get(
            "total_score",
            score_result.get("total_score")
        ),
        "summary_feedback": summary_result.get(
            "summary_feedback",
            feedback_result.get("summary")
        ),
        "error": data.get("error"),
        "processing_time_seconds": data.get("processing_time_seconds"),
        "metrics": metrics,
        "summary_api": f"/analyze/result/{result_id}/summary",
        "sections_api": f"/analyze/result/{result_id}/sections",
        "timeline_api": f"/analyze/result/{result_id}/timeline",
        "timeline_chart_api": f"/analyze/result/{result_id}/timeline/chart",
        "detail_api": f"/analyze/result/{result_id}"
    }


def list_analysis_results():
    os.makedirs(RESULT_DIR, exist_ok=True)

    results = []

    for filename in os.listdir(RESULT_DIR):
        if not filename.endswith(".json"):
            continue

        file_path = RESULT_DIR / filename

        try:
            with open(file_path, "r", encoding="utf-8") as f:
                result_file = json.load(f)

            results.append(
                build_result_summary_item(
                    result_file,
                    str(file_path)
                )
            )

        except Exception:
            continue

    results.sort(
        key=lambda x: x.get("created_at", ""),
        reverse=True
    )

    return results
