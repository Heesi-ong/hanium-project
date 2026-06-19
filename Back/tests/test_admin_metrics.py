import unittest
from decimal import Decimal
from unittest.mock import MagicMock, patch

from Back.app.routers.admin import get_admin_metrics
from Back.app.services.analysis_job_admin import get_admin_analysis_metrics


class AdminMetricsTest(unittest.TestCase):
    @patch("Back.app.services.analysis_job_admin.get_connection")
    def test_analysis_metrics_calculate_rates_without_user_data(self, get_connection):
        cursor = MagicMock()
        cursor.fetchone.return_value = {
            "total": 12,
            "completed": 8,
            "failed": 2,
            "cancelled": 1,
            "queued": 1,
            "processing": 0,
            "average_completed_processing_seconds": Decimal("42.345"),
            "created_last_24_hours": 4,
            "completed_last_24_hours": 3,
            "failed_last_24_hours": 1,
        }
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        get_connection.return_value = connection

        metrics = get_admin_analysis_metrics()

        self.assertEqual(metrics["success_rate"], 80)
        self.assertEqual(metrics["failure_rate"], 20)
        self.assertEqual(metrics["average_completed_processing_seconds"], 42.34)
        self.assertNotIn("email", metrics)
        connection.close.assert_called_once()

    @patch("Back.app.routers.admin.get_admin_user_metrics", return_value={"total": 2})
    @patch("Back.app.routers.admin.get_admin_analysis_metrics", return_value={"total": 3})
    def test_admin_metrics_endpoint_wraps_analysis_metrics(self, get_metrics, get_user_metrics):
        response = get_admin_metrics(_admin={"id": 1, "role": "admin"})

        self.assertEqual(response, {"analysis": {"total": 3}, "users": {"total": 2}})
        get_metrics.assert_called_once_with()
        get_user_metrics.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
