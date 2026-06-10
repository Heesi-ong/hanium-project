import threading
import time
from collections import defaultdict, deque

from fastapi import HTTPException, Request

_events = defaultdict(deque)
_lock = threading.Lock()


def enforce_rate_limit(request: Request, scope: str, limit: int, window_seconds: int):
    client_host = request.client.host if request.client else "unknown"
    key = (scope, client_host)
    now = time.monotonic()
    cutoff = now - window_seconds

    with _lock:
        events = _events[key]
        while events and events[0] <= cutoff:
            events.popleft()

        if len(events) >= limit:
            raise HTTPException(
                status_code=429,
                detail="요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
            )

        events.append(now)
