import unittest
from unittest.mock import Mock, patch

from fastapi import HTTPException

from Back.app.services import rate_limit


class RateLimitTests(unittest.TestCase):
    def setUp(self):
        rate_limit._store.clear()

    @patch("Back.app.services.rate_limit.time.monotonic", side_effect=[1, 2, 3])
    def test_rejects_requests_over_limit(self, _monotonic):
        request = Mock()
        request.client.host = "127.0.0.1"

        rate_limit.enforce_rate_limit(request, "login", limit=2, window_seconds=60)
        rate_limit.enforce_rate_limit(request, "login", limit=2, window_seconds=60)

        with self.assertRaises(HTTPException) as raised:
            rate_limit.enforce_rate_limit(request, "login", limit=2, window_seconds=60)

        self.assertEqual(raised.exception.status_code, 429)

    @patch("Back.app.services.rate_limit.time.monotonic", side_effect=[1, 70])
    def test_expired_events_do_not_count(self, _monotonic):
        request = Mock()
        request.client.host = "127.0.0.1"

        rate_limit.enforce_rate_limit(request, "upload", limit=1, window_seconds=60)
        rate_limit.enforce_rate_limit(request, "upload", limit=1, window_seconds=60)

    @patch("Back.app.services.rate_limit.time.monotonic", side_effect=[1, 2])
    def test_identity_scopes_limits_independently(self, _monotonic):
        request = Mock()
        request.client.host = "127.0.0.1"

        rate_limit.enforce_rate_limit(request, "login-email", 1, 60, identity="a@example.com")
        rate_limit.enforce_rate_limit(request, "login-email", 1, 60, identity="b@example.com")

    @patch("Back.app.services.rate_limit.time.monotonic", side_effect=[1, 2, 70])
    def test_expired_identity_keys_are_removed_during_periodic_cleanup(self, _monotonic):
        store = rate_limit.InMemoryRateLimitStore(cleanup_interval=3)

        store.consume(("login-email", "127.0.0.1", "a@example.com"), 1, 60)
        store.consume(("login-email", "127.0.0.1", "b@example.com"), 1, 60)
        store.consume(("login-email", "127.0.0.1", "current@example.com"), 1, 60)

        self.assertEqual(
            set(store.events),
            {("login-email", "127.0.0.1", "current@example.com")},
        )

    def test_clear_removes_event_and_window_metadata(self):
        store = rate_limit.InMemoryRateLimitStore()
        store.events["key"].append(1)
        store.windows["key"] = 60

        store.clear()

        self.assertEqual(store.events, {})
        self.assertEqual(store.windows, {})


if __name__ == "__main__":
    unittest.main()
