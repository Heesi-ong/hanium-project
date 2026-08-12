"""NVIDIA chat completion 응답 파싱과 공개 Video LLM 응답 정규화."""

import json
import logging
import math
from typing import Any, Dict


logger = logging.getLogger("video-llm-engine")

OBSERVATION_CATEGORIES = (
    "eyeContact",
    "facialExpression",
    "gesture",
    "posture",
)
SUMMARY_FIELDS = (
    "visualDelivery",
    "mainStrength",
    "mainWeakness",
)


def extract_chat_completion_content(response_json: Dict[str, Any]) -> str:
    try:
        content = response_json["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError(
            "NVIDIA response is missing choices[0].message.content."
        ) from exc

    if isinstance(content, str):
        return content

    if isinstance(content, list):
        text_parts = []
        for part in content:
            if not isinstance(part, dict) or part.get("type") != "text":
                continue
            text = part.get("text", "")
            if not isinstance(text, str):
                raise ValueError("NVIDIA response content must be a JSON string.")
            text_parts.append(text)

        joined = "".join(text_parts).strip()
        if joined:
            return joined

    raise ValueError("NVIDIA response content must be a JSON string.")


def parse_model_json(content: str) -> Dict[str, Any]:
    stripped = content.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        stripped = "\n".join(lines).strip()

    try:
        parsed = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise ValueError("NVIDIA response content is not valid JSON.") from exc

    if not isinstance(parsed, dict):
        raise ValueError("NVIDIA response JSON must be an object.")

    return parsed


def normalize_video_llm_response(
    job_id: str,
    model_name: str,
    model_json: Dict[str, Any],
    duration_sec: float | None = None,
) -> Dict[str, Any]:
    observations = model_json.get("observations")
    if not isinstance(observations, dict):
        raise ValueError("NVIDIA response is missing observations object.")

    normalized_observations = {
        category: normalize_observation_list(observations, category, duration_sec)
        for category in OBSERVATION_CATEGORIES
    }

    global_summary = model_json.get("globalSummary")
    if not isinstance(global_summary, dict):
        raise ValueError("NVIDIA response is missing globalSummary object.")

    normalized_summary = {}
    for field in SUMMARY_FIELDS:
        value = global_summary.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(
                f"NVIDIA response globalSummary.{field} must be a non-empty string."
            )
        normalized_summary[field] = value.strip()

    return {
        "jobId": job_id,
        "status": "success",
        "model": {
            "name": model_name,
            "version": "nvidia-nim",
            "generationMode": "REAL",
        },
        "observations": normalized_observations,
        "globalSummary": normalized_summary,
    }


def normalize_observation_list(
    observations: Dict[str, Any],
    category: str,
    duration_sec: float | None,
) -> list[Dict[str, Any]]:
    items = observations.get(category)
    if not isinstance(items, list):
        raise ValueError(f"NVIDIA response observations.{category} must be a list.")

    return [
        normalize_observation_item(item, category, index, duration_sec)
        for index, item in enumerate(items)
    ]


def normalize_observation_item(
    item: Any,
    category: str,
    index: int,
    duration_sec: float | None,
) -> Dict[str, Any]:
    if not isinstance(item, dict):
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}] must be an object."
        )

    raw_start_sec = require_number(item, "startSec", category, index)
    raw_end_sec = require_number(item, "endSec", category, index)
    if raw_end_sec < raw_start_sec:
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}] has endSec < startSec."
        )

    start_sec = clamp_observation_time(
        raw_start_sec, duration_sec, category, index, "startSec"
    )
    end_sec = clamp_observation_time(
        raw_end_sec, duration_sec, category, index, "endSec"
    )

    label = require_string(item, "label", category, index)
    description = require_string(item, "description", category, index)
    confidence = require_number(item, "confidence", category, index)
    if confidence < 0 or confidence > 1:
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].confidence must be between 0 and 1."
        )

    return {
        "startSec": start_sec,
        "endSec": end_sec,
        "label": label,
        "description": description,
        "confidence": confidence,
    }


def clamp_observation_time(
    value: int | float,
    duration_sec: float | None,
    category: str,
    index: int,
    field: str,
) -> int | float:
    if value < 0:
        logger.warning(
            "NVIDIA_VIDEO_LLM_TIME_CLAMP category=%s index=%s field=%s original=%s durationSec=%s reason=negative",
            category,
            index,
            field,
            value,
            duration_sec,
        )
        value = 0

    if duration_sec is None or value <= duration_sec:
        return value

    logger.warning(
        "NVIDIA_VIDEO_LLM_TIME_CLAMP category=%s index=%s field=%s original=%s durationSec=%s",
        category,
        index,
        field,
        value,
        duration_sec,
    )
    return duration_sec


def require_number(
    item: Dict[str, Any], field: str, category: str, index: int
) -> int | float:
    value = item.get(field)
    if (
        not isinstance(value, (int, float))
        or isinstance(value, bool)
        or not math.isfinite(value)
    ):
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].{field} must be a finite number."
        )
    return value


def require_string(item: Dict[str, Any], field: str, category: str, index: int) -> str:
    value = item.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(
            f"NVIDIA response observations.{category}[{index}].{field} must be a non-empty string."
        )
    return value.strip()
