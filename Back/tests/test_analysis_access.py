import unittest
from io import BytesIO
from unittest.mock import patch

from fastapi import HTTPException, UploadFile

from Back.app.routers.analyze import (
    _require_completed_result,
    _require_owned_job,
    cancel_job,
    delete_result,
    download_markdown_report,
    retry_job,
    upload_video,
)


class AnalysisAccessTests(unittest.TestCase):
    @patch("Back.app.routers.analyze_common.get_user_job")
    def test_owned_job_is_returned(self, get_user_job):
        get_user_job.return_value = {"result_id": "job-1", "status": "QUEUED"}

        result = _require_owned_job("job-1", 7)

        self.assertEqual(result["result_id"], "job-1")
        get_user_job.assert_called_once_with("job-1", 7)

    @patch("Back.app.routers.analyze_common.get_user_job", return_value=None)
    def test_other_users_job_is_hidden_as_not_found(self, _get_user_job):
        with self.assertRaises(HTTPException) as raised:
            _require_owned_job("job-1", 8)

        self.assertEqual(raised.exception.status_code, 404)

    @patch("Back.app.routers.analyze_common.get_user_job")
    def test_incomplete_job_cannot_expose_result(self, get_user_job):
        get_user_job.return_value = {"result_id": "job-1", "status": "PROCESSING"}

        with self.assertRaises(HTTPException) as raised:
            _require_completed_result("job-1", 7)

        self.assertEqual(raised.exception.status_code, 409)

    @patch("Back.app.routers.analyze_common.sync_job_summary")
    @patch("Back.app.routers.analyze_common.load_analysis_result")
    @patch("Back.app.routers.analyze_common.get_user_job")
    def test_completed_result_repairs_summary_mismatch(self, get_user_job, load_result, sync_summary):
        summary = {"total_score": 80, "summary_feedback": "좋음", "metrics": {"gaze_score": 80}}
        get_user_job.return_value = {"result_id": "job-1", "status": "COMPLETED", "total_score": 70}
        load_result.return_value = {"result_id": "job-1", "data": {"summary_result": summary}}

        result = _require_completed_result("job-1", 7)

        self.assertEqual(result["result_id"], "job-1")
        sync_summary.assert_called_once_with("job-1", summary)

    @patch("Back.app.routers.analyze_upload.enforce_rate_limit")
    @patch("Back.app.routers.analyze_upload.get_disk_status", return_value={"ok": True, "free_mb": 100000})
    def test_empty_video_is_rejected_before_job_creation(self, _disk_status, _enforce_rate_limit):
        file = UploadFile(file=BytesIO(b""), filename="empty.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 400)

    @patch("Back.app.routers.analyze_upload.enforce_rate_limit", side_effect=HTTPException(status_code=429))
    def test_upload_checks_rate_limit_before_storage_work(self, enforce_rate_limit):
        file = UploadFile(file=BytesIO(b"video"), filename="sample.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 429)
        enforce_rate_limit.assert_called_once()

    @patch("Back.app.routers.analyze_upload.enforce_rate_limit")
    @patch("Back.app.routers.analyze_upload.get_disk_status", return_value={"ok": False, "free_mb": 100})
    def test_upload_is_rejected_when_disk_space_is_low(self, _disk_status, _enforce_rate_limit):
        file = UploadFile(file=BytesIO(b"video"), filename="sample.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 507)

    @patch("Back.app.routers.analyze_upload.enforce_rate_limit")
    @patch("Back.app.routers.analyze_upload.get_disk_status", return_value={"ok": True, "free_mb": 100000})
    @patch(
        "Back.app.routers.analyze_upload.get_user_storage_usage",
        return_value={"active_analysis_count": 2, "available_bytes": 100000},
    )
    def test_upload_is_rejected_when_user_has_too_many_active_jobs(self, _storage, _disk, _rate_limit):
        file = UploadFile(file=BytesIO(b"video"), filename="sample.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 409)

    @patch("Back.app.routers.analyze_upload.enforce_rate_limit")
    @patch("Back.app.routers.analyze_upload.get_disk_status", return_value={"ok": True, "free_mb": 100000})
    @patch(
        "Back.app.routers.analyze_upload.get_user_storage_usage",
        return_value={"active_analysis_count": 0, "available_bytes": 1},
    )
    def test_upload_is_rejected_when_user_quota_is_exhausted(self, _storage, _disk, _rate_limit):
        file = UploadFile(file=BytesIO(b"video"), filename="sample.mp4")

        with self.assertRaises(HTTPException) as raised:
            upload_video(request=object(), file=file, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 413)

    @patch("Back.app.routers.analyze_upload.enforce_rate_limit")
    @patch(
        "Back.app.routers.analyze_upload.get_user_job_by_idempotency_key",
        return_value={"result_id": "existing-job", "status": "QUEUED"},
    )
    def test_upload_idempotency_key_returns_existing_job(self, _existing_job, _rate_limit):
        file = UploadFile(file=BytesIO(b"video"), filename="sample.mp4")

        result = upload_video(
            request=object(),
            file=file,
            idempotency_key="same-request",
            user={"id": 7},
        )

        self.assertEqual(result["job"]["result_id"], "existing-job")

    @patch("Back.app.routers.analyze_upload.request_user_job_cancel", return_value=False)
    def test_completed_job_cannot_be_cancelled(self, _cancel):
        with self.assertRaises(HTTPException) as raised:
            cancel_job("job-1", user={"id": 7})
        self.assertEqual(raised.exception.status_code, 409)

    @patch("Back.app.routers.analyze_upload.retry_user_job", return_value=True)
    @patch("Back.app.routers.analyze_upload.get_user_job_source_filename", return_value="source.mp4")
    @patch("Back.app.routers.analyze_common.get_user_job")
    @patch("pathlib.Path.exists", return_value=True)
    def test_owned_failed_job_can_be_retried(self, _exists, get_user_job, _source, _retry):
        get_user_job.side_effect = [
            {"result_id": "job-1", "status": "FAILED"},
            {"result_id": "job-1", "status": "QUEUED"},
        ]
        response = retry_job("job-1", user={"id": 7})
        self.assertEqual(response["job"]["status"], "QUEUED")

    @patch("Back.app.routers.analyze_deletion.StagedDeletion")
    @patch("Back.app.routers.analyze_deletion.get_user_job_source_filename", return_value="source.mp4")
    @patch("Back.app.routers.analyze_deletion.delete_result_records", return_value=2)
    @patch("Back.app.routers.analyze_common.get_user_job", return_value={"result_id": "job-1", "status": "COMPLETED"})
    def test_result_deletion_removes_connected_conversations(
        self,
        _job,
        delete_records,
        _filename,
        staged_deletion,
    ):
        response = delete_result("job-1", user={"id": 7})

        delete_records.assert_called_once_with("job-1", 7)
        staged_deletion.return_value.commit.assert_called_once_with()
        staged_deletion.return_value.purge.assert_called_once_with()
        self.assertEqual(response["deleted_conversations"], 2)

    @patch("Back.app.routers.analyze_deletion.StagedDeletion")
    @patch("Back.app.routers.analyze_deletion.get_user_job_source_filename", return_value="source.mp4")
    @patch("Back.app.routers.analyze_deletion.delete_result_records", side_effect=RuntimeError("db failed"))
    @patch("Back.app.routers.analyze_common.get_user_job", return_value={"result_id": "job-1", "status": "COMPLETED"})
    def test_result_deletion_restores_staged_files_when_database_delete_fails(
        self,
        _job,
        _delete_records,
        _filename,
        staged_deletion,
    ):
        with self.assertRaises(HTTPException) as raised:
            delete_result("job-1", user={"id": 7})

        self.assertEqual(raised.exception.status_code, 500)
        staged_deletion.return_value.rollback.assert_called_once_with()

    @patch("Back.app.routers.analyze_reports.require_completed_result")
    def test_markdown_report_handles_unavailable_frame_scores(self, completed_result):
        completed_result.return_value = {
            "result_id": "job-1",
            "created_at": "2026-06-18T00:00:00",
            "data": {
                "original_filename": "sample.mp4",
                "summary_result": {"total_score": 80, "metrics": {}},
                "score_result": {"total_score": 80},
                "feedback_result": {},
                "timeline_result": {
                    "timeline": [
                        {"time_sec": 0, "frame_score": None, "pose_score": None, "gaze_score": None},
                        {"time_sec": 1, "frame_score": 60.0, "pose_score": 70.0, "gaze_score": 50.0},
                    ]
                },
            },
        }

        response = download_markdown_report("job-1", user={"id": 7})

        body = response.body.decode("utf-8")
        self.assertEqual(response.status_code, 200)
        self.assertIn("측정 불가", body)
        self.assertIn("종합 60.0점", body)


if __name__ == "__main__":
    unittest.main()
