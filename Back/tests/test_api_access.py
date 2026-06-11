import unittest
from unittest.mock import patch

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

    def test_oversized_upload_is_rejected_before_authentication(self):
        with TestClient(app) as client:
            response = client.post("/analyze/upload", headers={"Content-Length": str(600 * 1024 * 1024)})
        self.assertEqual(response.status_code, 413)

    @patch("Back.app.routers.analyze.list_user_jobs")
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
