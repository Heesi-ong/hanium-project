import pytest
from fastapi import HTTPException, status

from app.core.security import verify_internal_api_key


def test_verify_internal_api_key_rejects_when_key_is_not_configured(monkeypatch):
    monkeypatch.delenv("INTERNAL_ENGINE_API_KEY", raising=False)

    with pytest.raises(HTTPException) as exc_info:
        verify_internal_api_key("request-key")

    assert exc_info.value.status_code == status.HTTP_401_UNAUTHORIZED
    assert exc_info.value.detail == "Internal engine API key is not configured."


@pytest.mark.parametrize("request_key", [None, "", "wrong-key"])
def test_verify_internal_api_key_rejects_missing_or_wrong_header(monkeypatch, request_key):
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", "shared-secret")

    with pytest.raises(HTTPException) as exc_info:
        verify_internal_api_key(request_key)

    assert exc_info.value.status_code == status.HTTP_401_UNAUTHORIZED
    assert exc_info.value.detail == "Invalid internal engine API key."


def test_verify_internal_api_key_accepts_matching_header(monkeypatch):
    monkeypatch.setenv("INTERNAL_ENGINE_API_KEY", "shared-secret")

    verify_internal_api_key("shared-secret")
