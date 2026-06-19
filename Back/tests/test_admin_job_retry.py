import unittest
from unittest.mock import MagicMock, patch

from Back.app.services.analysis_job_admin import retry_admin_job


class AdminJobRetryTest(unittest.TestCase):
    @patch("Back.app.services.analysis_job_admin.transaction")
    def test_successful_retry_records_admin_audit_in_same_transaction(self, transaction):
        cursor = MagicMock()
        cursor.rowcount = 1
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        transaction.return_value.__enter__.return_value = connection

        result = retry_admin_job(
            "job-1",
            {"id": 3, "email": "admin@example.com", "role": "admin"},
        )

        self.assertTrue(result)
        self.assertEqual(cursor.execute.call_count, 2)
        self.assertIn("UPDATE analysis_jobs", cursor.execute.call_args_list[0].args[0])
        self.assertIn("INSERT INTO admin_audit_logs", cursor.execute.call_args_list[1].args[0])

    @patch("Back.app.services.analysis_job_admin.transaction")
    def test_failed_retry_does_not_record_admin_audit(self, transaction):
        cursor = MagicMock()
        cursor.rowcount = 0
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        transaction.return_value.__enter__.return_value = connection

        result = retry_admin_job(
            "job-1",
            {"id": 3, "email": "admin@example.com", "role": "admin"},
        )

        self.assertFalse(result)
        self.assertEqual(cursor.execute.call_count, 1)


if __name__ == "__main__":
    unittest.main()
