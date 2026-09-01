import time

import httpx
import pytest

from app.services import nvidia_provider, nvidia_response, nvidia_runtime


def _make_http_status_error(status_code: int) -> httpx.HTTPStatusError:
    request = httpx.Request("POST", "https://example.test/chat/completions")
    response = httpx.Response(status_code, request=request)
    return httpx.HTTPStatusError(
        f"{status_code} error", request=request, response=response
    )


def _call_chat_completion(monkeypatch, tmp_path, execute_side_effects):
    """execute_side_effects: 매 호출마다 순서대로 소비되는 예외/반환값 목록."""
    calls = {"count": 0}

    def fake_execute_chat_completion(**_kwargs):
        outcome = execute_side_effects[calls["count"]]
        calls["count"] += 1
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    monkeypatch.setattr(
        nvidia_provider, "execute_chat_completion", fake_execute_chat_completion
    )
    monkeypatch.setattr(
        nvidia_response, "extract_chat_completion_content", lambda response_json: "ok"
    )
    monkeypatch.setattr(
        nvidia_response, "parse_model_json", lambda content: {"parsed": content}
    )
    monkeypatch.setattr(time, "sleep", lambda _seconds: None)

    video_path = tmp_path / "segment.mp4"
    video_path.write_bytes(b"fake")

    result = nvidia_runtime.call_chat_completion(
        api_key="key",
        model="model",
        base_url="https://example.test",
        asset_base_url="https://example.test/assets",
        timeout_seconds=30,
        job_id="job-1-segment-0",
        video_path=video_path,
        content_type="video/mp4",
        duration_hint_sec=5.0,
        sample_fps=1,
        max_frames=10,
        deadline_monotonic=None,
    )
    return result, calls["count"]


def test_call_chat_completion_retries_on_transient_5xx_then_succeeds(
    monkeypatch, tmp_path
):
    success = nvidia_provider.ChatCompletionResult(
        response_json={"choices": []}, response_status="200"
    )
    result, call_count = _call_chat_completion(
        monkeypatch,
        tmp_path,
        [_make_http_status_error(503), success],
    )

    assert call_count == 2
    assert result == {"parsed": "ok"}


def test_call_chat_completion_gives_up_after_max_transient_retries(
    monkeypatch, tmp_path
):
    with pytest.raises(httpx.HTTPStatusError) as exc_info:
        _call_chat_completion(
            monkeypatch,
            tmp_path,
            [
                _make_http_status_error(503),
                _make_http_status_error(502),
                _make_http_status_error(429),
            ],
        )

    assert exc_info.value.response.status_code == 429


def test_call_chat_completion_does_not_retry_non_transient_4xx(monkeypatch, tmp_path):
    with pytest.raises(httpx.HTTPStatusError) as exc_info:
        _call_chat_completion(
            monkeypatch,
            tmp_path,
            [_make_http_status_error(401)],
        )

    assert exc_info.value.response.status_code == 401
