import re
from decimal import Decimal

from .practice_contexts import (
    delete_practice_context,
    find_previous_same_series,
    list_orphan_practice_contexts,
    list_practice_series,
    load_practice_context,
    order_growth,
    save_practice_context,
)

__all__ = [
    "build_practice_coaching",
    "delete_practice_context",
    "enrich_growth",
    "find_previous_same_series",
    "list_orphan_practice_contexts",
    "list_practice_series",
    "load_practice_context",
    "save_practice_context",
]

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


def _score(value):
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        return None
    return float(value)


def _legacy_series(context):
    source = context.get("series_id_source")
    return bool(context.get("series_name")) and (not source or str(source).startswith("legacy"))


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
            (
                f"현재 {audio.get('speech_speed_spm')} 음절/분을 기준으로 핵심 문단을 또박또박 다시 말합니다."
                if audio.get("speech_speed_spm")
                else f"현재 {audio.get('speech_speed_wpm', '-')} WPM을 기준으로 핵심 문단을 또박또박 다시 말합니다."
            ),
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
    audio_result = data.get("audio_result", {})
    transcript = str(audio_result.get("text") or "").strip()
    segments = [
        {
            "start": float(segment.get("start", 0)),
            "end": float(segment.get("end", 0)),
            "text": str(segment.get("text") or "").strip(),
        }
        for segment in audio_result.get("segments", [])
        if str(segment.get("text") or "").strip()
    ]
    if not transcript:
        return {
            "available": False,
            "note": "음성 인식 텍스트가 없어 발표 내용 구조는 평가하지 않았습니다.",
            "structure": [],
            "evidence": [],
        }
    sentences = segments or [{"start": None, "end": None, "text": transcript}]
    duration = max((item["end"] for item in segments), default=0)
    thirds = (
        ("도입", 0, duration * 0.25, ("오늘", "주제", "발표", "문제", "목적")),
        ("본론", duration * 0.20, duration * 0.80, ("첫째", "둘째", "이유", "결과", "방법", "근거")),
        ("결론", duration * 0.75, duration + 1, ("결론", "정리", "따라서", "마지막", "제안")),
    )
    structure = []
    for part, start, end, keywords in thirds:
        candidates = [item for item in sentences if item["start"] is None or start <= item["start"] <= end]
        keyword_evidence = next(
            (item for item in candidates if any(keyword in item["text"].lower() for keyword in keywords)),
            None,
        )
        evidence = keyword_evidence
        structure.append(
            {
                "part": part,
                "found": bool(evidence),
                "confidence": "medium" if evidence else "low",
                "start": evidence.get("start") if evidence else None,
                "end": evidence.get("end") if evidence else None,
                "sentence": evidence.get("text") if evidence else None,
            }
        )
    longest_item = max(sentences, key=lambda item: len(item["text"]))
    longest = longest_item["text"]
    shortened = longest[:100].rstrip(" ,") + ("..." if len(longest) > 100 else "")
    core_words = [word for word in re.findall(r"[가-힣A-Za-z0-9]+", core_message) if len(word) >= 2]
    message_mentioned = bool(core_words) and any(word in transcript for word in core_words[:5])
    stopwords = {
        "그리고", "그래서", "하지만", "저희", "제가", "이번", "대한", "통해", "것을",
        "수", "있는", "있습니다", "합니다", "입니다", "됩니다",
    }
    words = [
        word
        for word in re.findall(r"[가-힣A-Za-z0-9]+", transcript.lower())
        if len(word) >= 2 and word not in stopwords
    ]
    repeated = sorted(
        ((word, words.count(word)) for word in set(words) if words.count(word) >= 3),
        key=lambda item: item[1],
        reverse=True,
    )[:5]
    return {
        "available": True,
        "method": "heuristic",
        "confidence": "보조 분석",
        "note": "Whisper 발화 시점과 연결 표현을 사용하는 휴리스틱 보조 분석입니다. 의미 판단이 필요한 부분은 직접 확인하세요.",
        "structure": structure,
        "core_message_mentioned": message_mentioned if core_words else None,
        "repeated_expressions": [{"expression": word, "count": count} for word, count in repeated],
        "flow": {
            "has_order_markers": any(word in transcript for word in ("첫째", "둘째", "다음", "따라서", "정리")),
            "note": "순서·인과·정리 연결 표현의 존재 여부를 확인했습니다.",
        },
        "evidence": [
            {
                "issue": "가장 긴 설명 구간",
                "start": longest_item.get("start"),
                "end": longest_item.get("end"),
                "sentence": longest[:300],
                "rewrite_example": (
                    f"예시 수정: '핵심 주장은 {shortened}입니다. 이를 뒷받침하는 근거는 [수치·사례]입니다.'처럼 "
                    "주장과 검증 근거를 분리하세요."
                ),
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
    visual_rates = [value for value in (score.get("pose_detection_rate"), score.get("face_detection_rate")) if isinstance(value, (int, float))]
    confidence = {
        "visual": "높음" if visual_rates and min(visual_rates) >= 70 else "제한적",
        "audio": "높음" if score.get("audio_analysis_available") else "제한적",
        "note": "감지 데이터가 부족한 항목은 낮은 점수가 아닌 확인 필요 항목으로 안내합니다.",
    }
    comparison = None
    if previous:
        current_score = _score(data.get("summary_result", {}).get("total_score"))
        previous_score = _score(previous.get("total_score"))
        if current_score is not None and previous_score is not None:
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
        "series_compatibility_note": (
            "기존 이름 기반 시리즈는 같은 이름의 다른 발표가 포함될 수 있습니다."
            if _legacy_series(context)
            else None
        ),
    }


def enrich_growth(growth, user_id):
    enriched = []
    previous_by_series = {}
    for item in order_growth(growth):
        context = load_practice_context(item["result_id"], user_id) or {}
        current_score = _score(item.get("total_score"))
        series_key = (
            context.get("purpose"),
            context.get("series_id")
            or (
                f"legacy:{context.get('series_name', '').strip().casefold()}"
                if context.get("series_name")
                else ""
            ),
        )
        previous = previous_by_series.get(series_key) if series_key[1] else None
        change = None
        if previous is not None and current_score is not None:
            change = round(current_score - previous, 2)
        enriched.append(
            {
                **item,
                "practice_context": context,
                "purpose_label": PURPOSES.get(context.get("purpose"), {}).get("label"),
                "score_change": change,
                "series_compatibility_note": (
                    "기존 이름 기반 시리즈 비교입니다. 같은 이름의 다른 발표 포함 여부를 확인하세요."
                    if _legacy_series(context)
                    else None
                ),
            }
        )
        if current_score is not None and series_key[1]:
            previous_by_series[series_key] = current_score
    return enriched
