"""로그인, 업로드, 채팅 같은 민감 작업에 메모리 기반 요청 제한을 적용한다."""

import threading
import time
from collections import defaultdict, deque

from fastapi import HTTPException, Request


class InMemoryRateLimitStore:
    def __init__(self, cleanup_interval=100):
        self.events = defaultdict(deque)
        self.windows = {}
        self.lock = threading.Lock()
        self.cleanup_interval = cleanup_interval
        self.consume_count = 0

    def consume(self, key, limit, window_seconds):
        now = time.monotonic()
        cutoff = now - window_seconds
        with self.lock:
            self.consume_count += 1
            self.windows[key] = window_seconds
            events = self.events[key]
            while events and events[0] <= cutoff:
                events.popleft()
            if len(events) >= limit:
                self._cleanup_expired_keys(now)
                return False
            events.append(now)
            self._cleanup_expired_keys(now)
            return True

    def clear(self):
        with self.lock:
            self.events.clear()
            self.windows.clear()
            self.consume_count = 0

    def _cleanup_expired_keys(self, now):
        if self.consume_count % self.cleanup_interval:
            return
        expired_keys = [
            key
            for key, events in self.events.items()
            if not events or events[-1] <= now - self.windows.get(key, 0)
        ]
        for key in expired_keys:
            self.events.pop(key, None)
            self.windows.pop(key, None)


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
