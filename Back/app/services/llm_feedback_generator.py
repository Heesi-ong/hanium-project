"""LLM 2차 피드백 생성 진입점을 제공한다.

현재는 mock LLM 응답을 반환하며, 실제 Ollama/OpenAI 연결 시 call_llm만 교체한다.
"""

from .llm_prompt_builder import build_llm_feedback_prompt


def _issue_messages(filtered_analysis_result):
    return [
        issue.get("message", "")
        for issue in filtered_analysis_result.get("priority_issues", [])
        if issue.get("message")
    ]


def _strength_messages(filtered_analysis_result):
    return [
        strength.get("message", "")
        for strength in filtered_analysis_result.get("strengths", [])
        if strength.get("message")
    ]


def _practice_plan(filtered_analysis_result):
    targets = filtered_analysis_result.get("improvement_targets", [])
    if not targets:
        return [
            "현재 강점을 유지하면서 같은 조건으로 한 번 더 촬영해 결과가 안정적인지 확인합니다.",
            "발표 시작 30초와 마무리 30초를 따로 반복해 전달력을 점검합니다.",
            "분석 결과에서 측정 불가 항목이 있다면 촬영 환경과 음성 입력 상태를 먼저 확인합니다.",
        ]

    plan = [f"{target}을 3분 단위로 끊어 반복 연습합니다." for target in targets[:3]]
    while len(plan) < 3:
        plan.append("다음 촬영 전에 핵심 메시지를 한 문장으로 다시 정리합니다.")
    return plan


def call_llm(prompt: dict) -> dict:
    """임시 LLM 호출 함수.

    실제 연동 시 이 함수 내부를 Ollama 또는 OpenAI API 호출로 교체한다.
    """

    filtered = prompt.get("input_data", {}).get("filtered_analysis_result", {})
    raw = prompt.get("input_data", {}).get("raw_analysis_result", {})
    summary = raw.get("summary", {})
    score = summary.get("total_score")
    overall_level = filtered.get("overall_level", "측정 불가")
    strengths = _strength_messages(filtered)
    weaknesses = _issue_messages(filtered)

    if score is None:
        summary_text = f"분석 가능한 항목을 기준으로 보면 현재 발표 수준은 '{overall_level}'입니다."
    else:
        summary_text = f"종합 점수 {score}점을 기준으로 현재 발표 수준은 '{overall_level}'입니다."

    return {
        "summary": summary_text,
        "strengths": strengths[:3] or ["분석 가능한 항목을 기준으로 발표 상태를 확인했습니다."],
        "weaknesses": weaknesses[:3] or ["우선 개선이 필요한 항목이 뚜렷하게 감지되지 않았습니다."],
        "detailed_feedback": {
            "speech": _speech_feedback(filtered),
            "posture": _posture_feedback(filtered),
            "gaze": _gaze_feedback(filtered),
            "face": _face_feedback(filtered),
        },
        "practice_plan": _practice_plan(filtered),
        "final_advice": "다음 연습에서는 가장 우선순위가 높은 개선 항목 1개만 정해 다시 촬영하고 결과 변화를 비교하세요.",
        "metadata": {
            "provider": "mock",
            "prompt_version": prompt.get("prompt_version"),
        },
    }


def _has_issue(filtered_analysis_result, category):
    return any(
        issue.get("category") == category
        for issue in filtered_analysis_result.get("priority_issues", [])
    )


def _speech_feedback(filtered_analysis_result):
    parts = []
    if _has_issue(filtered_analysis_result, "speech_speed"):
        parts.append("발화 속도가 권장 범위를 벗어나 청중이 내용을 따라가기 어려울 수 있습니다.")
    if _has_issue(filtered_analysis_result, "silence"):
        parts.append("침묵 구간이 발표 흐름을 끊을 수 있으므로 문장 사이 호흡을 일정하게 조절해야 합니다.")
    if _has_issue(filtered_analysis_result, "filler"):
        parts.append("필러워드는 핵심 문장 앞에서 잠시 멈춘 뒤 말하는 방식으로 줄이는 것이 좋습니다.")
    return " ".join(parts) or "발화 속도, 침묵, 필러워드에서 즉시 조정해야 할 큰 문제는 제한적으로 감지되었습니다."


def _posture_feedback(filtered_analysis_result):
    if _has_issue(filtered_analysis_result, "posture"):
        return "어깨 균형이 흔들려 보일 수 있으므로 카메라 중앙에 선 뒤 양쪽 어깨 높이를 맞추는 연습이 필요합니다."
    if _has_issue(filtered_analysis_result, "pose_detection"):
        return "자세 감지율이 낮아 실제 자세 평가보다 촬영 환경 점검이 먼저 필요합니다."
    return "자세 관련 지표는 현재 분석 기준에서 비교적 안정적으로 해석됩니다."


def _gaze_feedback(filtered_analysis_result):
    if _has_issue(filtered_analysis_result, "gaze"):
        return "시선이 분산되어 보일 수 있으므로 핵심 문장마다 카메라나 청중 방향을 2초 이상 유지하는 연습이 필요합니다."
    return "시선 처리에서 우선 개선이 필요한 큰 문제는 제한적으로 감지되었습니다."


def _face_feedback(filtered_analysis_result):
    if _has_issue(filtered_analysis_result, "face_detection"):
        return "얼굴 감지율이 낮아 카메라 위치, 조명, 얼굴 방향을 먼저 조정해야 합니다."
    return "얼굴 방향 관련 지표는 현재 입력 데이터 안에서 큰 위험 신호가 제한적입니다."


def generate_llm_feedback(raw_analysis_result: dict, filtered_analysis_result: dict) -> dict:
    prompt = build_llm_feedback_prompt(raw_analysis_result, filtered_analysis_result)
    return call_llm(prompt)
