from typing import Any, Mapping


MIN_DURATION_FOR_FORCED_SEGMENTATION_SEC = 30.0

_SYSTEM_PROMPT = (
    "/no_think\n"
    "You are a presentation-coaching video analyst. Return only strict JSON. "
    "Do not wrap the JSON in Markdown. The JSON must match the requested schema exactly."
)


def build_duration_prompt(duration_sec: float | None) -> str:
    """영상 길이에 맞는 timestamp 관찰 지시문을 생성합니다.

    짧은 영상은 실제 관찰 시점을 그대로 보고하도록 하고, 30초 이상 영상은 모델이
    전체 구간 하나로 결과를 뭉개지 않도록 정확히 세 구간의 경계를 제공합니다.
    """
    if duration_sec is None:
        return ""

    if duration_sec < MIN_DURATION_FOR_FORCED_SEGMENTATION_SEC:
        return (
            f"The video is exactly {duration_sec:.3f} seconds long. "
            f"All startSec and endSec values must be within [0, {duration_sec:.3f}]. "
            "Report the real moments you actually observe with their true timestamps. "
            "Do not force the video into artificial sub-segments: if a behavior genuinely "
            f"spans the whole clip, report one observation covering [0, {duration_sec:.3f}]; "
            "if distinct moments are visible, report them separately with their actual timing. "
        )

    first_boundary = duration_sec / 3
    second_boundary = duration_sec * 2 / 3
    return (
        f"The video is exactly {duration_sec:.3f} seconds long. "
        f"All startSec and endSec values must be within [0, {duration_sec:.3f}] "
        "and must not all be 0 unless the entire observation truly spans the whole video. "
        f"Divide the video into three temporal segments: [0, {first_boundary:.3f}), "
        f"[{first_boundary:.3f}, {second_boundary:.3f}), "
        f"and [{second_boundary:.3f}, {duration_sec:.3f}]. "
        "For each observation category (eyeContact, facialExpression, gesture, posture), "
        "include at least one observation for each segment when the behavior is actually visible "
        "in that segment. Unless the behavior truly does not change for the whole video, "
        "do not collapse all observations into a single [0, duration] range. "
    )


def build_nvidia_chat_completion_payload(
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
    model: str,
    video_input: Mapping[str, str | None],
) -> dict[str, Any]:
    """NVIDIA chat/completions의 inline/asset별 요청 payload를 생성합니다."""
    user_prompt = _build_user_prompt(duration_hint_sec, sample_fps, max_frames)

    if video_input.get("asset_id"):
        return _build_asset_payload(model, user_prompt, video_input)

    return _build_inline_payload(model, user_prompt, video_input)


def _build_user_prompt(
    duration_hint_sec: float | None,
    sample_fps: int,
    max_frames: int,
) -> str:
    duration_prompt = build_duration_prompt(duration_hint_sec)
    return (
        "Analyze the uploaded presentation video for visible delivery behavior. "
        "Return JSON with this exact shape: "
        "{"
        '"observations":{'
        '"eyeContact":[{"startSec":0,"endSec":0,"label":"string",'
        '"description":"string","confidence":0.0}],'
        '"facialExpression":[{"startSec":0,"endSec":0,"label":"string",'
        '"description":"string","confidence":0.0}],'
        '"gesture":[{"startSec":0,"endSec":0,"label":"string",'
        '"description":"string","confidence":0.0}],'
        '"posture":[{"startSec":0,"endSec":0,"label":"string",'
        '"description":"string","confidence":0.0}]'
        "},"
        '"globalSummary":{'
        '"visualDelivery":"string",'
        '"mainStrength":"string",'
        '"mainWeakness":"string"'
        "}"
        "}. "
        "Use seconds from the start of the video. Keep confidence between 0 and 1. "
        f"{duration_prompt}"
        f"Sampling hint from caller: sampleFps={sample_fps}, maxFrames={max_frames}."
    )


def _common_payload(model: str) -> dict[str, Any]:
    return {
        "model": model,
        "temperature": 0.2,
        "max_tokens": 1200,
        "response_format": {"type": "json_object"},
    }


def _build_asset_payload(
    model: str,
    user_prompt: str,
    video_input: Mapping[str, str | None],
) -> dict[str, Any]:
    payload = _common_payload(model)
    payload["messages"] = [
        {
            "role": "user",
            "content": (
                f"/no_think\n{user_prompt}\n"
                "Return only valid JSON. Do not include Markdown, comments, or trailing text.\n"
                f'<video src="{video_input["url"]}" />'
            ),
        },
    ]
    return payload


def _build_inline_payload(
    model: str,
    user_prompt: str,
    video_input: Mapping[str, str | None],
) -> dict[str, Any]:
    payload = _common_payload(model)
    payload["messages"] = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": user_prompt},
                {
                    "type": "video_url",
                    "video_url": {"url": video_input["url"]},
                },
            ],
        },
    ]
    return payload
