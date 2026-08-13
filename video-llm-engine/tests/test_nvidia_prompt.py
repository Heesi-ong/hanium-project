import re

from app.services import nvidia_prompt


COMMON_EXPECTED_FIELDS = {
    "model": "nvidia/test-model",
    "temperature": 0.2,
    "max_tokens": 1200,
    "response_format": {"type": "json_object"},
}


def test_build_duration_prompt_without_duration_keeps_prompt_optional():
    assert nvidia_prompt.build_duration_prompt(None) == ""


def test_build_duration_prompt_uses_observed_timestamps_for_short_video():
    prompt = nvidia_prompt.build_duration_prompt(12.5)

    assert "The video is exactly 12.500 seconds long." in prompt
    assert "within [0, 12.500]" in prompt
    assert "Report the real moments you actually observe" in prompt
    assert "Divide the video into three temporal segments" not in prompt


def test_build_duration_prompt_has_exactly_three_contiguous_long_video_segments():
    prompt = nvidia_prompt.build_duration_prompt(60.0)
    segment_text = prompt.split(
        "Divide the video into three temporal segments: ", maxsplit=1
    )[1].split(". For each observation category", maxsplit=1)[0]
    ranges = [
        (float(start), float(end))
        for start, end in re.findall(
            r"\[(\d+(?:\.\d{3})?),\s*(\d+(?:\.\d{3})?)[\)\]]",
            segment_text,
        )
    ]

    assert "Divide the video into three temporal segments" in prompt
    assert ranges == [
        (0.0, 20.0),
        (20.0, 40.0),
        (40.0, 60.0),
    ]


def test_build_inline_payload_preserves_system_text_video_and_json_contract():
    payload = nvidia_prompt.build_nvidia_chat_completion_payload(
        duration_hint_sec=4.166,
        sample_fps=2,
        max_frames=45,
        model="nvidia/test-model",
        video_input={
            "url": "data:video/mp4;base64,AAAA",
            "asset_id": None,
            "content_type": "video/mp4",
        },
    )

    for key, expected in COMMON_EXPECTED_FIELDS.items():
        assert payload[key] == expected
    assert [message["role"] for message in payload["messages"]] == ["system", "user"]
    assert payload["messages"][0]["content"].startswith("/no_think\n")
    user_content = payload["messages"][1]["content"]
    assert user_content[0]["type"] == "text"
    assert "The video is exactly 4.166 seconds long." in user_content[0]["text"]
    assert "sampleFps=2, maxFrames=45" in user_content[0]["text"]
    assert user_content[1] == {
        "type": "video_url",
        "video_url": {"url": "data:video/mp4;base64,AAAA"},
    }


def test_build_asset_payload_preserves_single_message_and_asset_reference():
    payload = nvidia_prompt.build_nvidia_chat_completion_payload(
        duration_hint_sec=60.0,
        sample_fps=1,
        max_frames=90,
        model="nvidia/test-model",
        video_input={
            "url": "data:video/mp4;asset_id,asset-123",
            "asset_id": "asset-123",
            "content_type": "video/mp4",
        },
    )

    for key, expected in COMMON_EXPECTED_FIELDS.items():
        assert payload[key] == expected
    assert len(payload["messages"]) == 1
    assert payload["messages"][0]["role"] == "user"
    content = payload["messages"][0]["content"]
    assert content.startswith("/no_think\n")
    assert "Divide the video into three temporal segments" in content
    assert '<video src="data:video/mp4;asset_id,asset-123" />' in content
    assert "Return only valid JSON" in content
