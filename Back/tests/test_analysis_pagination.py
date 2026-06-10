import unittest

from Back.app.services.analysis_jobs import _decode_job_cursor, _encode_job_cursor


class AnalysisPaginationTest(unittest.TestCase):
    def test_cursor_round_trip(self):
        cursor = _encode_job_cursor({"created_at": "2026-06-11 10:00:00", "result_id": "job-1"})
        self.assertEqual(_decode_job_cursor(cursor), ("2026-06-11 10:00:00", "job-1"))

    def test_invalid_cursor_is_ignored(self):
        self.assertIsNone(_decode_job_cursor("not-a-valid-cursor"))


if __name__ == "__main__":
    unittest.main()
