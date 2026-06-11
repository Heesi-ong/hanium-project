import threading
import time
from collections import defaultdict, deque

from fastapi import HTTPException, Request


class InMemoryRateLimitStore:
    def __init__(self):
        self.events = defaultdict(deque)
        self.lock = threading.Lock()

    def consume(self, key, limit, window_seconds):
        now = time.monotonic()
        cutoff = now - window_seconds
        with self.lock:
            events = self.events[key]
            while events and events[0] <= cutoff:
                events.popleft()
            if len(events) >= limit:
                return False
            events.append(now)
            return True


_store = InMemoryRateLimitStore()
_events = _store.events


def enforce_rate_limit(request: Request, scope: str, limit: int, window_seconds: int, identity=None):
    client_host = request.client.host if request.client else "unknown"
    key = (scope, client_host, str(identity or ""))
    if not _store.consume(key, limit, window_seconds):
        raise HTTPException(
            status_code=429,
            detail="요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
        )
