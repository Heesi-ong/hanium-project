import unittest
from unittest.mock import patch
from uuid import UUID

from fastapi import HTTPException
from fastapi.testclient import TestClient

from Back.app.main import app
from Back.app.services.auth_service import get_current_user


class ApiAccessTests(unittest.TestCase):
    def tearDown(self):
        app.dependency_overrides.clear()

    def test_analysis_results_requires_login(self):
        with TestClient(app) as client:
            response = client.get("/analyze/results")
        self.assertEqual(response.status_code, 401)
        self.assertTrue(response.headers.get("X-Request-ID"))

    def test_request_id_is_preserved(self):
        with TestClient(app) as client:
            response = client.get("/", headers={"X-Request-ID": "test-request-id"})
        self.assertEqual(response.headers["X-Request-ID"], "test-request-id")

    def test_invalid_request_id_is_replaced(self):
        with TestClient(app) as client:
            response = client.get("/", headers={"X-Request-ID": "invalid request id"})
        self.assertNotEqual(response.headers["X-Request-ID"], "invalid request id")
        UUID(response.headers["X-Request-ID"])

    def test_oversized_request_id_is_replaced(self):
        with TestClient(app) as client:
            response = client.get("/", headers={"X-Request-ID": "a" * 129})
        self.assertNotEqual(response.headers["X-Request-ID"], "a" * 129)
        UUID(response.headers["X-Request-ID"])

    def test_security_headers_are_applied(self):
        with TestClient(app) as client:
            response = client.get("/")
        self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(response.headers["X-Frame-Options"], "DENY")
        self.assertIn("frame-ancestors 'none'", response.headers["Content-Security-Policy"])

    def test_cross_origin_cookie_mutation_is_rejected(self):
        with TestClient(app) as client:
            response = client.post(
                "/api/auth/logout",
                headers={"Origin": "https://attacker.example"},
                cookies={"session_token": "fake"},
            )
        self.assertEqual(response.status_code, 403)

    @patch("Back.app.routers.auth.enforce_rate_limit", side_effect=HTTPException(status_code=429))
    def test_password_change_checks_rate_limit_before_database(self, enforce_rate_limit):
        app.dependency_overrides[get_current_user] = lambda: {"id": 77}
        with TestClient(app) as client:
            response = client.put(
                "/api/auth/password",
                json={"current_password": "current-password", "new_password": "new-password"},
            )
        self.assertEqual(response.status_code, 429)
        self.assertEqual(enforce_rate_limit.call_args.kwargs["identity"], "77")

    @patch("Back.app.routers.auth.enforce_rate_limit", side_effect=HTTPException(status_code=429))
    def test_login_checks_rate_limit_before_database(self, enforce_rate_limit):
        with TestClient(app) as client:
            response = client.post(
                "/api/auth/login",
                json={"email": "user@example.com", "password": "password"},
            )
        self.assertEqual(response.status_code, 429)
        enforce_rate_limit.assert_called()

    @patch("Back.app.routers.auth.enforce_rate_limit", side_effect=HTTPException(status_code=429))
    def test_account_deletion_checks_rate_limit_before_database(self, enforce_rate_limit):
        app.dependency_overrides[get_current_user] = lambda: {"id": 77}
        with TestClient(app) as client:
            response = client.request("DELETE", "/api/auth/account", json={"password": "current-password"})
        self.assertEqual(response.status_code, 429)
        self.assertEqual(enforce_rate_limit.call_args.kwargs["identity"], "77")

    def test_oversized_upload_is_rejected_before_authentication(self):
        with TestClient(app) as client:
            response = client.post("/analyze/upload", headers={"Content-Length": str(600 * 1024 * 1024)})
        self.assertEqual(response.status_code, 413)

    @patch("Back.app.routers.analyze_results.list_user_jobs")
    def test_analysis_results_use_authenticated_user(self, list_user_jobs):
        list_user_jobs.return_value = {"results": [], "total": 0, "summary": {}}
        app.dependency_overrides[get_current_user] = lambda: {"id": 77}
        with TestClient(app) as client:
            response = client.get("/analyze/results?limit=5")
        self.assertEqual(response.status_code, 200)
        list_user_jobs.assert_called_once_with(
            77, status=None, search="", sort="latest", limit=5, offset=0, cursor=None
        )


if __name__ == "__main__":
    unittest.main()
