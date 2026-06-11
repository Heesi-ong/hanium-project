import json
import os
import re
import tempfile

from ..config import BACK_DIR
from .file_cleaner import ensure_file_removed, safe_remove_file

PRACTICE_CONTEXT_DIR = BACK_DIR / "practice_contexts"

PURPOSES = {
    "class": {
        "label": "대학 수업 발표",
        "recommended_minutes": 10,
        "focus": "핵심 개념을 정확하고 이해하기 쉽게 설명하는 발표",
        "questions": ["이 발표의 핵심 개념을 한 문장으로 설명해 주세요.", "근거 자료의 출처와 한계는 무엇인가요?"],
    },
    "project": {
        "label": "프로젝트 발표",
        "recommended_minutes": 12,
        "focus": "문제, 해결 과정, 결과와 기여도를 논리적으로 전달하는 발표",
        "questions": ["이 프로젝트가 해결하는 가장 중요한 문제는 무엇인가요?", "본인의 기여와 검증 결과를 설명해 주세요."],
    },
    "interview": {
        "label": "면접·자기소개",
        "recommended_minutes": 3,
        "focus": "강점과 경험을 구체적인 근거로 짧고 설득력 있게 전달하는 발표",
        "questions": ["이 경험에서 본인이 직접 해결한 문제는 무엇인가요?", "지원 역할에서 이 강점을 어떻게 활용할 수 있나요?"],
    },
    "business": {
        "label": "업무 보고",
        "recommended_minutes": 7,
        "focus": "현황, 원인, 의사결정 사항과 다음 행동을 간결하게 전달하는 발표",
        "questions": ["현재 가장 큰 위험과 대응 계획은 무엇인가요?", "청중이 오늘 결정해야 하는 사항은 무엇인가요?"],
    },
    "sales": {
        "label": "세일즈·제안 발표",
        "recommended_minutes": 10,
        "focus": "고객 문제와 제안 가치, 근거, 다음 행동을 설득력 있게 전달하는 발표",
        "questions": ["경쟁 대안보다 이 제안이 나은 이유는 무엇인가요?", "도입 효과를 어떤 지표로 검증할 수 있나요?"],
    },
}


def _path(result_id):
    return PRACTICE_CONTEXT_DIR / f"{result_id}.json"


def save_practice_context(result_id, user_id, context):
    PRACTICE_CONTEXT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {"result_id": result_id, "user_id": user_id, **context}
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


def _score(value):
    return value if isinstance(value, (int, float)) else None


def _improvement_candidates(data):
    score = data.get("score_result", {})
    audio = data.get("audio_result", {})
    filler = data.get("filler_result", {})
    candidates = [
        (
            "시선 연결",
            _score(score.get("gaze_score")),
            "핵심 문장을 말할 때 카메라를 청중이라고 생각하고 3초 이상 정면을 바라보세요.",
            "도입과 결론을 각각 30초씩 녹화하며 문장 끝마다 정면 응시를 유지합니다.",
        ),
        (
            "자세 안정성",
            _score(score.get("shoulder_balance_score")),
            "양발에 체중을 고르게 두고 문단이 바뀔 때만 의도적으로 움직이세요.",
            "1분 발표를 촬영하고 불필요한 몸 흔들림 횟수를 직접 확인합니다.",
        ),
        (
            "말하기 속도",
            _score(score.get("speech_speed_score")),
            "핵심 문장 앞뒤에 짧은 멈춤을 넣어 청중이 내용을 처리할 시간을 주세요.",
            f"현재 {audio.get('speech_speed_wpm', '-')} WPM을 기준으로 핵심 문단을 또박또박 다시 말합니다.",
        ),
        (
            "필러 단어",
            _score(filler.get("filler_score")),
            "생각할 시간이 필요할 때 필러 단어 대신 1초간 침묵하세요.",
            f"자주 사용한 표현 {', '.join(list((filler.get('filler_words') or {}).keys())[:3]) or '없음'}을 의식하며 1분 답변을 연습합니다.",
        ),
        (
            "제스처",
            _score(score.get("gesture_score")),
            "숫자, 비교, 방향을 설명하는 문장에만 손동작을 연결하세요.",
            "핵심 메시지 세 문장에 사용할 제스처를 하나씩 정해 반복합니다.",
        ),
        (
            "음량 전달력",
            _score(score.get("volume_score")),
            "문장 첫 단어를 분명하게 시작하고 문장 끝의 음량이 줄지 않도록 하세요.",
            "카메라에서 두 걸음 떨어져 핵심 메시지를 세 번 녹음하고 음량을 비교합니다.",
        ),
    ]
    return sorted(candidates, key=lambda item: item[1] if item[1] is not None else 101)


def _build_content_analysis(data, core_message):
    transcript = str(data.get("audio_result", {}).get("text") or "").strip()
    if not transcript:
        return {
            "available": False,
            "note": "음성 인식 텍스트가 없어 발표 내용 구조는 평가하지 않았습니다.",
            "structure": [],
            "evidence": [],
        }
    sentences = [sentence.strip() for sentence in re.split(r"(?<=[.!?。])\s+|\n+", transcript) if sentence.strip()]
    lowered = transcript.lower()
    structure = [
        {"part": "도입", "found": any(word in lowered for word in ("오늘", "주제", "발표", "문제"))},
        {"part": "본론", "found": any(word in lowered for word in ("첫째", "둘째", "이유", "결과", "방법"))},
        {"part": "결론", "found": any(word in lowered for word in ("결론", "정리", "따라서", "마지막"))},
    ]
    longest = max(sentences, key=len) if sentences else transcript[:240]
    shortened = longest[:100].rstrip(" ,") + ("..." if len(longest) > 100 else "")
    core_words = [word for word in re.findall(r"[가-힣A-Za-z0-9]+", core_message) if len(word) >= 2]
    message_mentioned = bool(core_words) and any(word in transcript for word in core_words[:5])
    return {
        "available": True,
        "note": "음성 인식 텍스트의 연결 표현과 문장 길이를 기준으로 한 보조 분석입니다.",
        "structure": structure,
        "core_message_mentioned": message_mentioned if core_words else None,
        "evidence": [
            {
                "issue": "가장 긴 문장",
                "sentence": longest[:240],
                "rewrite_example": f"핵심 주장부터 말한 뒤 근거를 나누어 설명하세요. 예: {shortened}",
            }
        ],
    }


def build_practice_coaching(result, context, previous=None):
    data = result.get("data", {})
    purpose = PURPOSES.get(context.get("purpose"), PURPOSES["project"])
    duration = data.get("video_info", {}).get("duration_seconds")
    target_minutes = context.get("target_minutes") or purpose["recommended_minutes"]
    plan = []
    for title, score, action, exercise in _improvement_candidates(data)[:3]:
        plan.append(
            {
                "title": title,
                "score": score,
                "reason": "현재 분석에서 우선 개선 효과가 큰 항목입니다." if score is not None else "측정 데이터가 부족해 확인이 필요한 항목입니다.",
                "action": action,
                "exercise": exercise,
            }
        )

    audience = context.get("audience") or "일반 청중"
    core_message = context.get("core_message") or "아직 입력하지 않음"
    questions = [
        *purpose["questions"],
        f"{audience}이 가장 궁금해할 반론이나 우려는 무엇인가요?",
        f"핵심 메시지 '{core_message}'를 뒷받침하는 가장 강한 근거는 무엇인가요?",
    ]
    score = data.get("score_result", {})
    confidence = {
        "visual": "높음" if min(score.get("pose_detection_rate", 0), score.get("face_detection_rate", 0)) >= 70 else "제한적",
        "audio": "높음" if score.get("audio_analysis_available") else "제한적",
        "note": "감지 데이터가 부족한 항목은 낮은 점수가 아닌 확인 필요 항목으로 안내합니다.",
    }
    comparison = None
    if previous:
        current_score = data.get("summary_result", {}).get("total_score")
        previous_score = previous.get("total_score")
        if isinstance(current_score, (int, float)) and isinstance(previous_score, (int, float)):
            comparison = {"previous_score": previous_score, "score_change": round(current_score - previous_score, 2)}

    return {
        "context": context,
        "purpose": {**purpose, "key": context.get("purpose", "project")},
        "duration_fit": {
            "actual_minutes": round(duration / 60, 1) if isinstance(duration, (int, float)) else None,
            "target_minutes": target_minutes,
        },
        "improvement_plan": plan,
        "expected_questions": questions,
        "content_analysis": _build_content_analysis(data, context.get("core_message", "")),
        "confidence": confidence,
        "comparison": comparison,
    }


def enrich_growth(growth, user_id):
    enriched = []
    previous = None
    for item in growth:
        context = load_practice_context(item["result_id"], user_id) or {}
        current_score = item.get("total_score")
        change = None
        if previous is not None and isinstance(current_score, (int, float)) and isinstance(previous, (int, float)):
            change = round(current_score - previous, 2)
        enriched.append(
            {
                **item,
                "practice_context": context,
                "purpose_label": PURPOSES.get(context.get("purpose"), {}).get("label"),
                "score_change": change,
            }
        )
        if isinstance(current_score, (int, float)):
            previous = current_score
    return enriched
