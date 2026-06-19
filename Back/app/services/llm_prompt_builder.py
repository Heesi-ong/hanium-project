"""기존 분석 결과와 1차 필터링 결과를 LLM 입력 데이터로 변환한다."""

import json

LLM_FEEDBACK_PROMPT_VERSION = "presentation-feedback-filtered-2026.06.1"

LLM_OUTPUT_SCHEMA = {
    "summary": "전체 발표에 대한 요약 피드백",
    "strengths": ["잘한 점 1", "잘한 점 2"],
    "weaknesses": ["개선할 점 1", "개선할 점 2"],
    "detailed_feedback": {
        "speech": "발화 속도, 침묵, 필러워드에 대한 설명",
        "posture": "자세와 어깨 균형에 대한 설명",
        "gaze": "시선 처리에 대한 설명",
        "face": "얼굴 방향과 표정 관련 설명",
    },
    "practice_plan": ["다음 연습 방법 1", "다음 연습 방법 2", "다음 연습 방법 3"],
    "final_advice": "사용자가 다음 발표에서 바로 적용할 수 있는 조언",
}


def _compact_raw_analysis(raw_analysis_result):
    raw = raw_analysis_result or {}
    score = raw.get("score_result", {})
    audio = raw.get("audio_result", {})
    video = raw.get("video_info", {})
    summary = raw.get("summary_result", {})
    feedback = raw.get("feedback_result", {})

    return {
        "video_info": {
            "duration_seconds": video.get("duration_seconds"),
            "fps": video.get("fps"),
            "width": video.get("width"),
            "height": video.get("height"),
        },
        "summary": {
            "total_score": summary.get("total_score", score.get("total_score")),
            "summary_feedback": summary.get("summary_feedback", feedback.get("summary")),
        },
        "speech": {
            "speech_speed_wpm": audio.get("speech_speed_wpm", score.get("speech_speed_wpm")),
            "speech_speed_spm": audio.get("speech_speed_spm", score.get("speech_speed_spm")),
            "speech_speed_basis": audio.get("speech_speed_basis", score.get("speech_speed_basis")),
            "silence_count": audio.get("silence_count", score.get("silence_count")),
            "total_silence_time": audio.get("total_silence_time", score.get("total_silence_time")),
            "filler_count": audio.get("filler_count", score.get("filler_count")),
            "filler_words": audio.get("filler_words", score.get("filler_words")),
        },
        "visual": {
            "pose_detection_rate": score.get("pose_detection_rate"),
            "face_detection_rate": score.get("face_detection_rate"),
            "shoulder_balance_score": score.get("shoulder_balance_score"),
            "gaze_score": score.get("gaze_score"),
            "head_direction_score": score.get("head_direction_score"),
            "gesture_score": score.get("gesture_score"),
        },
        "voice": {
            "volume_score": score.get("volume_score"),
            "mean_volume_db": score.get("mean_volume_db"),
            "max_volume_db": score.get("max_volume_db"),
            "volume_level": score.get("volume_level"),
        },
        "analysis_confidence": score.get("analysis_confidence"),
        "basic_feedback": feedback,
    }


def build_llm_feedback_prompt(raw_analysis_result: dict, filtered_analysis_result: dict) -> dict:
    """LLM 호출부가 그대로 사용할 수 있는 구조화 프롬프트를 만든다."""

    prompt = {
        "prompt_version": LLM_FEEDBACK_PROMPT_VERSION,
        "system_instruction": (
            "사용자는 발표 연습자이다. 피드백은 비난이 아니라 개선 중심으로 작성한다. "
            "점수만 나열하지 말고 실제 발표 상황에 맞게 설명한다. "
            "분석 데이터에 없는 내용은 추측하지 않는다. "
            "강점, 문제점, 개선 방법, 다음 연습 방법을 구분해서 작성한다. "
            "결과는 지정된 JSON 형식으로만 반환한다."
        ),
        "output_schema": LLM_OUTPUT_SCHEMA,
        "input_data": {
            "raw_analysis_result": _compact_raw_analysis(raw_analysis_result),
            "filtered_analysis_result": filtered_analysis_result or {},
        },
    }
    return json.loads(json.dumps(prompt, ensure_ascii=False, default=str))
