"""로컬 발표 코칭 지식 문서를 파싱하고 질문·지표에 맞는 참고 문서를 검색한다."""

import logging
import re
from threading import Lock

from ..config import KNOWLEDGE_DIR, RAG_MAX_CHARS_PER_DOCUMENT, RAG_MAX_DOCUMENTS

logger = logging.getLogger(__name__)
_cache_lock = Lock()
_knowledge_cache = {"signature": None, "documents": None}

PRIORITY_SCORES = {"high": 3, "medium": 2, "low": 1}
PURPOSE_DOCUMENTS = {
    "class": {"academic_presentation_guide"},
    "project": {"academic_presentation_guide"},
    "interview": {"interview_presentation_guide"},
    "business": {"business_presentation_guide"},
    "sales": {"business_presentation_guide"},
}
METRIC_ALIASES = {
    "total_score": {"총점", "점수", "scoring"},
    "shoulder_balance_score": {"자세", "어깨", "posture"},
    "gaze_score": {"시선", "카메라", "eye", "contact"},
    "speech_speed_score": {"발표", "속도", "말하기", "wpm"},
    "silence_score": {"침묵", "멈춤", "pause", "silence"},
    "filler_score": {"필러", "어", "음", "filler"},
    "gesture_score": {"제스처", "손동작", "gesture"},
    "volume_score": {"음량", "발성", "volume"},
}
METRIC_DOCUMENT_TARGETS = {
    "total_score": {"scoring_criteria", "total_score_interpretation", "total_score"},
    "shoulder_balance_score": {"posture_criteria", "posture_score"},
    "gaze_score": {"eye_contact", "eye_contact_score"},
    "speech_speed_score": {"speech_speed_wpm", "wpm"},
    "silence_score": {"pause_silence", "silence_duration"},
    "filler_score": {"filler_words", "filler_count"},
    "gesture_score": {"gesture_criteria", "gesture_score"},
    "volume_score": {"voice_volume", "volume_stability"},
}


def _parse_scalar(value):
    text = value.strip().strip('"').strip("'")
    try:
        return float(text) if "." in text else int(text)
    except ValueError:
        return text


def _parse_document(path):
    raw = path.read_text(encoding="utf-8").strip()
    metadata = {}
    body = raw
    if raw.startswith("---\n"):
        _, front_matter, body = raw.split("---", 2)
        current_list = None
        for line in front_matter.splitlines():
            if line.startswith("  - ") and current_list:
                metadata[current_list].append(_parse_scalar(line[4:]))
                continue
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            key = key.strip()
            value = value.strip()
            if value:
                metadata[key] = _parse_scalar(value)
                current_list = None
            else:
                metadata[key] = []
                current_list = key
    title_match = re.search(r"^#\s+(.+)$", body, re.MULTILINE)
    title = title_match.group(1).strip() if title_match else path.stem
    return {
        "id": str(metadata.get("id") or path.stem),
        "title": title,
        "category": str(metadata.get("category") or ""),
        "target_metric": str(metadata.get("target_metric") or ""),
        "related_services": [str(item) for item in metadata.get("related_services", [])],
        "priority": str(metadata.get("priority") or "low"),
        "version": str(metadata.get("version") or ""),
        "source": path.name,
        "content": body.strip()[:RAG_MAX_CHARS_PER_DOCUMENT],
    }


def load_knowledge_documents():
    if not KNOWLEDGE_DIR.exists():
        return []
    paths = [
        path
        for path in sorted(KNOWLEDGE_DIR.glob("*.md"))
        if path.name.casefold() != "readme.md" and not path.is_symlink()
    ]
    try:
        signature = (
            str(KNOWLEDGE_DIR.resolve()),
            tuple((path.name, path.stat().st_mtime_ns, path.stat().st_size) for path in paths),
        )
    except OSError:
        logger.exception("Failed to inspect knowledge documents")
        return []
    with _cache_lock:
        if _knowledge_cache["signature"] == signature:
            return list(_knowledge_cache["documents"])

    documents = []
    document_ids = set()
    for path in paths:
        try:
            document = _parse_document(path)
        except (OSError, UnicodeError, ValueError):
            logger.exception("Failed to parse knowledge document: %s", path)
            continue
        if document["id"] in document_ids:
            logger.warning("Skipped duplicate knowledge document id %s from %s", document["id"], path)
            continue
        document_ids.add(document["id"])
        documents.append(document)
    with _cache_lock:
        _knowledge_cache["signature"] = signature
        _knowledge_cache["documents"] = tuple(documents)
    return documents


def _tokens(value):
    return {
        token.casefold()
        for token in re.findall(r"[가-힣A-Za-z0-9_]+", str(value))
        if len(token) >= 2
    }


def retrieve_knowledge(query, purpose=None, metric_keys=None, service="ai_coaching", limit=None):
    query_tokens = _tokens(query)
    metric_keys = set(metric_keys or [])
    expanded_tokens = set(query_tokens)
    for metric in metric_keys:
        expanded_tokens.update(_tokens(metric))
        expanded_tokens.update(METRIC_ALIASES.get(metric, set()))
    purpose_ids = PURPOSE_DOCUMENTS.get(purpose, set())
    scored = []
    for document in load_knowledge_documents():
        searchable = " ".join(
            [
                document["id"],
                document["title"],
                document["category"],
                document["target_metric"],
                " ".join(document["related_services"]),
                document["content"],
            ]
        )
        overlap = len(expanded_tokens & _tokens(searchable))
        relevance = overlap * 2
        if document["id"] in purpose_ids:
            relevance += 8
        if document["target_metric"] in metric_keys:
            relevance += 10
        if any(
            document["id"] in METRIC_DOCUMENT_TARGETS.get(metric, set())
            or document["target_metric"] in METRIC_DOCUMENT_TARGETS.get(metric, set())
            for metric in metric_keys
        ):
            relevance += 12
        if service in document["related_services"]:
            relevance += 5
        if service == "chat" and document["category"] in {"qa", "safety"}:
            relevance += 8
        if relevance > 0:
            score = relevance + PRIORITY_SCORES.get(document["priority"], 0)
            scored.append((score, document))
    selected = sorted(scored, key=lambda item: (-item[0], item[1]["source"]))[
        : limit or RAG_MAX_DOCUMENTS
    ]
    return [
        {
            **document,
            "retrieval_score": score,
            "usage_note": "코칭 지침용 참고 문서이며 시스템 측정 근거로 사용하지 않습니다.",
        }
        for score, document in selected
    ]


def clear_knowledge_cache():
    with _cache_lock:
        _knowledge_cache["signature"] = None
        _knowledge_cache["documents"] = None
