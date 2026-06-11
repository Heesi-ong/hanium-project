import unittest
from unittest.mock import patch

from fastapi import HTTPException

from Back.app.routers.practice import PracticeContextRequest, get_practice_coaching, update_practice_context


class PracticeAccessTests(unittest.TestCase):
    @patch("Back.app.routers.practice.save_practice_context")
    @patch("Back.app.routers.practice.get_user_job", return_value={"result_id": "job-1", "status": "QUEUED"})
    def test_owner_can_save_context_before_analysis_finishes(self, _job, save_context):
        request = PracticeContextRequest(
            purpose="project",
            audience="심사위원",
            target_minutes=10,
            core_message="사용자 가치를 높인다",
            series_name="최종 발표",
        )
        save_context.return_value = {"purpose": "project"}

        result = update_practice_context("job-1", request, user={"id": 7})

        self.assertEqual(result["practice_context"]["purpose"], "project")
        save_context.assert_called_once()

    @patch("Back.app.routers.practice.get_user_job", return_value=None)
    def test_other_users_job_is_hidden(self, _job):
        with self.assertRaises(HTTPException) as raised:
            update_practice_context(
                "job-1",
                PracticeContextRequest(purpose="class"),
                user={"id": 8},
            )
        self.assertEqual(raised.exception.status_code, 404)

    @patch("Back.app.routers.practice.get_user_job", return_value={"result_id": "job-1", "status": "PROCESSING"})
    def test_coaching_waits_for_completed_result(self, _job):
        with self.assertRaises(HTTPException) as raised:
            get_practice_coaching("job-1", user={"id": 7})
        self.assertEqual(raised.exception.status_code, 409)


if __name__ == "__main__":
    unittest.main()
