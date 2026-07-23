import secrets

from fastapi import Header, HTTPException, status

from app.core.settings import get_settings

INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"


def verify_internal_api_key(
        x_internal_api_key: str | None = Header(default=None, alias=INTERNAL_API_KEY_HEADER),
) -> None:
    configured_api_key = get_settings().internal_engine_api_key

    # Fail closed: this endpoint accepts server-side video paths, so a missing
    # shared secret must not silently turn authentication off.
    if not configured_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Internal engine API key is not configured.",
        )

    if x_internal_api_key is None or not secrets.compare_digest(
            x_internal_api_key,
            configured_api_key,
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid internal engine API key.",
        )
