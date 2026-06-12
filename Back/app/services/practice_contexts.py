import json
import os
import tempfile
from uuid import uuid4

from ..config import BACK_DIR
from .file_cleaner import ensure_file_removed, safe_remove_file

PRACTICE_CONTEXT_DIR = BACK_DIR / "practice_contexts"


def _path(result_id):
    return PRACTICE_CONTEXT_DIR / f"{result_id}.json"


def save_practice_context(result_id, user_id, context):
    PRACTICE_CONTEXT_DIR.mkdir(parents=True, exist_ok=True)
    series_name = str(context.get("series_name") or "").strip()
    series_id = context.get("series_id") or (str(uuid4()) if series_name else None)
    series_id_source = context.get("series_id_source") or (
        "selected" if context.get("series_id") else ("generated" if series_id else None)
    )
    payload = {
        "result_id": result_id,
        "user_id": user_id,
        **context,
        "series_name": series_name,
        "series_id": series_id,
        "series_id_source": series_id_source,
    }
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=PRACTICE_CONTEXT_DIR, delete=False) as file:
            temp_path = file.name
            json.dump(payload, file, ensure_ascii=False, indent=2)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp_path, _path(result_id))
    finally:
        if temp_path and os.path.exists(temp_path):
            safe_remove_file(temp_path)
    return payload


def load_practice_context(result_id, user_id):
    try:
        with open(_path(result_id), "r", encoding="utf-8") as file:
            context = json.load(file)
        return context if context.get("user_id") == user_id else None
    except (OSError, json.JSONDecodeError):
        return None


def delete_practice_context(result_id):
    return ensure_file_removed(_path(result_id))


def list_orphan_practice_contexts(valid_result_ids):
    valid_ids = set(valid_result_ids)
    if not PRACTICE_CONTEXT_DIR.exists():
        return []
    return sorted(path.stem for path in PRACTICE_CONTEXT_DIR.glob("*.json") if path.stem not in valid_ids)


def list_practice_series(user_id):
    if not PRACTICE_CONTEXT_DIR.exists():
        return []
    series = {}
    for path in PRACTICE_CONTEXT_DIR.glob("*.json"):
        try:
            context = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if context.get("user_id") != user_id or not context.get("series_name"):
            continue
        series_id = context.get("series_id")
        legacy = not context.get("series_id_source") or str(context.get("series_id_source")).startswith("legacy")
        key = series_id or f"legacy:{context.get('purpose')}:{context['series_name'].strip().casefold()}"
        series[key] = {
            "series_id": series_id,
            "series_name": context["series_name"],
            "purpose": context.get("purpose", "project"),
            "legacy": legacy,
            "compatibility_note": (
                "기존 이름 기반 시리즈입니다. 같은 이름의 다른 발표가 포함될 가능성을 확인하세요."
                if legacy
                else None
            ),
        }
    return sorted(series.values(), key=lambda item: (item["purpose"], item["series_name"].casefold()))


def same_series(left, right):
    if not left or not right or left.get("purpose") != right.get("purpose"):
        return False
    if left.get("series_id") and right.get("series_id"):
        return left["series_id"] == right["series_id"]
    if left.get("series_id") or right.get("series_id"):
        return False
    return bool(left.get("series_name")) and left.get("series_name").strip().casefold() == right.get(
        "series_name", ""
    ).strip().casefold()


def order_growth(growth):
    return sorted(
        growth,
        key=lambda item: (
            str(item.get("completed_at") or ""),
            str(item.get("created_at") or ""),
            item["result_id"],
        ),
    )


def find_previous_same_series(growth, user_id, result_id, current_context):
    previous = None
    for item in order_growth(growth):
        if item["result_id"] == result_id:
            break
        context = load_practice_context(item["result_id"], user_id) or {}
        if same_series(context, current_context):
            previous = item
    return previous
