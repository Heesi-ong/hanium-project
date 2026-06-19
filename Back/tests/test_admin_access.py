import unittest
from unittest.mock import patch

from fastapi import HTTPException

from Back.app.routers.admin import require_admin, retry_problem_job, update_user_status


class AdminAccessTest(unittest.TestCase):
    def test_regular_user_cannot_access_admin_status(self):
        with self.assertRaises(HTTPException) as raised:
            require_admin({"id": 7, "role": "user"})

        self.assertEqual(raised.exception.status_code, 403)

    def test_admin_can_access_admin_status(self):
        user = {"id": 1, "role": "admin"}
        self.assertEqual(require_admin(user), user)

    @patch("Back.app.routers.admin.retry_admin_job", return_value=True)
    def test_retry_passes_authenticated_admin_to_audited_service(self, retry_admin_job):
        admin = {"id": 1, "email": "admin@example.com", "role": "admin"}

        self.assertEqual(retry_problem_job("job-1", admin=admin), {"message": "analysis requeued"})

        retry_admin_job.assert_called_once_with("job-1", admin)

    @patch("Back.app.routers.admin.update_regular_user_status", return_value="admin_forbidden")
    def test_admin_account_status_cannot_be_changed_from_web(self, update_status):
        with self.assertRaises(HTTPException) as raised:
            update_user_status(
                2,
                status="disabled",
                admin={"id": 1, "email": "admin@example.com", "role": "admin"},
            )

        self.assertEqual(raised.exception.status_code, 403)
        update_status.assert_called_once()


if __name__ == "__main__":
    unittest.main()
