import unittest
from unittest.mock import patch

from fastapi import HTTPException

from Back.app.routers.practice import (
    PracticeContextRequest,
    create_ai_coaching,
    get_ai_coaching,
    get_practice_coaching,
    get_series,
    regenerate_ai_coaching,
    update_practice_context,
)


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

    @patch("Back.app.routers.practice.list_practice_series")
    def test_existing_series_can_be_listed_and_selected(self, list_series):
        list_series.return_value = [
            {"series_id": "series-1", "series_name": "한이음 발표", "purpose": "project"}
        ]

        self.assertEqual(get_series(user={"id": 7})["series"][0]["series_id"], "series-1")

        with patch("Back.app.routers.practice.get_user_job", return_value={"status": "QUEUED"}), patch(
            "Back.app.routers.practice.save_practice_context"
        ) as save_context:
            save_context.return_value = {"series_id": "series-1"}
            result = update_practice_context(
                "job-1",
                PracticeContextRequest(purpose="project", series_id="series-1"),
                user={"id": 7},
            )

        self.assertEqual(result["practice_context"]["series_id"], "series-1")

    @patch("Back.app.routers.practice.load_ai_coaching", return_value=None)
    @patch("Back.app.routers.practice.get_user_job", return_value=None)
    def test_other_users_ai_coaching_is_hidden(self, _job, _load):
        with self.assertRaises(HTTPException) as raised:
            get_ai_coaching("job-1", user={"id": 8})
        self.assertEqual(raised.exception.status_code, 404)

    @patch("Back.app.routers.practice.load_ai_coaching")
    @patch("Back.app.routers.practice.get_user_job", return_value={"status": "COMPLETED"})
    def test_saved_ai_coaching_is_returned_without_regeneration(self, _job, load):
        load.return_value = {"result_id": "job-1", "status": "completed"}

        response = create_ai_coaching("job-1", user={"id": 7})

        self.assertTrue(response["cached"])
        self.assertEqual(response["ai_coaching"]["status"], "completed")

    @patch("Back.app.routers.practice.generate_ai_coaching")
    @patch("Back.app.routers.practice._coaching_inputs")
    def test_regenerate_replaces_saved_ai_coaching(self, inputs, generate):
        inputs.return_value = ({"data": {}}, {"purpose": "project"}, None, {"improvement_plan": []})
        generate.return_value = {"result_id": "job-1", "status": "completed"}

        response = regenerate_ai_coaching("job-1", user={"id": 7})

        self.assertFalse(response["cached"])
        generate.assert_called_once()


if __name__ == "__main__":
    unittest.main()
