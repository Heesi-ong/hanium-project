import time


def remaining_timeout_seconds(
    deadline_monotonic: float | None,
    configured_timeout_seconds: float,
    operation: str,
) -> float:
    if deadline_monotonic is None:
        return configured_timeout_seconds

    remaining_seconds = deadline_monotonic - time.monotonic()
    if remaining_seconds <= 0:
        raise TimeoutError(
            f"Video LLM 전체 요청 deadline을 초과했습니다. operation={operation}"
        )
    return min(configured_timeout_seconds, remaining_seconds)


def ensure_within(deadline_monotonic: float | None, operation: str) -> None:
    remaining_timeout_seconds(
        deadline_monotonic,
        float("inf"),
        operation,
    )
