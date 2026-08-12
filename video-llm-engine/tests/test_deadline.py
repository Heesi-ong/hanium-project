import pytest

from app.services import deadline


def test_remaining_timeout_uses_configured_value_without_deadline():
    assert deadline.remaining_timeout_seconds(None, 30, "unit-test") == 30


def test_remaining_timeout_uses_shorter_deadline_budget(monkeypatch):
    monkeypatch.setattr(deadline.time, "monotonic", lambda: 100.0)

    assert deadline.remaining_timeout_seconds(104.5, 30, "unit-test") == 4.5
    assert deadline.remaining_timeout_seconds(200.0, 30, "unit-test") == 30


def test_expired_deadline_fails_with_operation_name(monkeypatch):
    monkeypatch.setattr(deadline.time, "monotonic", lambda: 100.0)

    with pytest.raises(TimeoutError, match="operation=video_download"):
        deadline.ensure_within(100.0, "video_download")
