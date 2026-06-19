import unittest
from unittest.mock import MagicMock, patch

from Back.app.services.admin_users import get_admin_user_metrics, list_admin_users, update_regular_user_status


class AdminUsersTest(unittest.TestCase):
    @patch("Back.app.services.admin_users.get_connection")
    def test_metrics_return_counts_without_user_identifiers(self, get_connection):
        cursor = MagicMock()
        cursor.fetchone.return_value = {
            "total": 3,
            "active": 2,
            "disabled": 1,
            "admins": 1,
            "created_last_24_hours": 1,
        }
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        get_connection.return_value = connection

        metrics = get_admin_user_metrics()

        self.assertEqual(metrics["active"], 2)
        self.assertNotIn("email", metrics)

    @patch("Back.app.services.admin_users.get_connection")
    def test_user_list_exposes_only_allowed_fields(self, get_connection):
        cursor = MagicMock()
        cursor.fetchone.return_value = {"count": 1}
        cursor.fetchall.return_value = [
            {
                "id": 7,
                "email": "user@example.com",
                "status": "active",
                "created_at": "2026-06-15",
                "status_change_allowed": 1,
            }
        ]
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        get_connection.return_value = connection

        result = list_admin_users(search="user", limit=10, offset=0)

        self.assertEqual(
            set(result["users"][0]),
            {"id", "email", "status", "created_at", "status_change_allowed"},
        )

    @patch("Back.app.services.admin_users.transaction")
    def test_disabling_regular_user_removes_sessions_and_records_audit(self, transaction):
        cursor = MagicMock()
        cursor.fetchone.return_value = {
            "id": 7,
            "email": "user@example.com",
            "role": "user",
            "status": "active",
        }
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        transaction.return_value.__enter__.return_value = connection

        outcome = update_regular_user_status(
            7,
            "disabled",
            {"id": 1, "email": "admin@example.com"},
        )

        self.assertEqual(outcome, "updated")
        queries = [call.args[0] for call in cursor.execute.call_args_list]
        self.assertTrue(any("DELETE FROM user_sessions" in query for query in queries))
        self.assertTrue(any("INSERT INTO admin_audit_logs" in query for query in queries))

    @patch("Back.app.services.admin_users.transaction")
    def test_admin_status_cannot_be_changed_from_web_service(self, transaction):
        cursor = MagicMock()
        cursor.fetchone.return_value = {
            "id": 1,
            "email": "admin@example.com",
            "role": "admin",
            "status": "active",
        }
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        transaction.return_value.__enter__.return_value = connection

        outcome = update_regular_user_status(
            1,
            "disabled",
            {"id": 2, "email": "other-admin@example.com"},
        )

        self.assertEqual(outcome, "admin_forbidden")


if __name__ == "__main__":
    unittest.main()
