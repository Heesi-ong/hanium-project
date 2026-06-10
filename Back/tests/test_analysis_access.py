import unittest
from io import BytesIO
from unittest.mock import patch

from fastapi import HTTPException, UploadFile

from Back.app.routers.analyze import (
    _require_completed_result,
    _require_owned_job,
    cancel_job,
    retry_job,
    upload_video,
)


class AnalysisAccessTests(unittest.TestCase):
    @patch("Back.app.routers.analyze.get_user_job")
    def test_owned_job_is_returned(self, get_user_job):
        get_user_job.return_value = {"result_id": "job-1", "status": "QUEUED"}

        result = _require_owned_job("job-1", 7)

        self.assertEqual(result["result_id"], "job-1")
        get_user_job.assert_called_once_with("job-1", 7)

    @patch("Back.app.routers.analyze.get_user_job", return_value=None)
    def test_other_users_job_is_hidden_as_not_found(self, _get_user_job):
        with self.assertRaises(HTTPException) as raised:
            _require_owned_job("job-1", 8)

        self.assertEqual(raised.exception.status_code, 404)

    @patch("Back.app.routers.analyze.get_user_job")
    def test_incomplete_job_cannot_expose_result(self, get_user_job):
        get_user_job.return_value = {"result_id": "job-1", "status": "PROCESSING"}

        with self.assertRaises(HTTPException) as raised:
            _require_completed_result("job-1", 7)

        self.assertEqual(raised.exception.status_code, 409)

    @patch("Back.app.routers.analyze.sync_job_summary")
    @patch("Back.app.routers.analyze.load_analysis_result")
    @patch("Back.app.routers.analyze.get_user_job")
    def test_completed_result_repairs_summary_mismatch(self, get_user_job, load_result, sync_summary):
        summary = {"total_score": 80, "summary_feedback": "좋음", "metrics": {"gaze_score": 80}}
        get_user_job.return_value = {"result_id": "job-1", "status": "COMPLETED", "total_score": 70}
        load_result.return_value = {"result_id": "job-1", "data": {"summary_result": summary}}

        result = _require_completed_result("job-1", 7)

        self.assertEqual(result["result_id"], "job-1")
        sync_summary.assert_called_once_with("job-1", summary)

    @patch("Back.app.routers.analyze.enforce_rate_limit")
    @patch("Back.app.routers.analyze.get_disk_status", return_value={"ok": True, "free_mb": 100000})
    def test_empty_video_is_rejected_before_job_creation(self, _disk_status, _enforce_rate_limit):
        file = UploadFile(file=BytesIO(b""), filename="empty.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 400)

    @patch("Back.app.routers.analyze.enforce_rate_limit")
    @patch("Back.app.routers.analyze.get_disk_status", return_value={"ok": False, "free_mb": 100})
    def test_upload_is_rejected_when_disk_space_is_low(self, _disk_status, _enforce_rate_limit):
        file = UploadFile(file=BytesIO(b"video"), filename="sample.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 507)

    @patch("Back.app.routers.analyze.request_user_job_cancel", return_value=False)
    def test_completed_job_cannot_be_cancelled(self, _cancel):
        with self.assertRaises(HTTPException) as raised:
            cancel_job("job-1", user={"id": 7})
        self.assertEqual(raised.exception.status_code, 409)

    @patch("Back.app.routers.analyze.retry_user_job", return_value=True)
    @patch("Back.app.routers.analyze.get_user_job_source_filename", return_value="source.mp4")
    @patch("Back.app.routers.analyze.get_user_job")
    @patch("pathlib.Path.exists", return_value=True)
    def test_owned_failed_job_can_be_retried(self, _exists, get_user_job, _source, _retry):
        get_user_job.side_effect = [
            {"result_id": "job-1", "status": "FAILED"},
            {"result_id": "job-1", "status": "QUEUED"},
        ]
        response = retry_job("job-1", user={"id": 7})
        self.assertEqual(response["job"]["status"], "QUEUED")


if __name__ == "__main__":
    unittest.main()
