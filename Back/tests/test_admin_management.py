import unittest
from unittest.mock import MagicMock, patch

from Back.app.services.admin_management import (
    AdminManagementError,
    create_initial_admin,
    promote_user_to_admin,
)


class AdminManagementTest(unittest.TestCase):
    @patch("Back.app.services.admin_management.transaction")
    @patch("Back.app.services.admin_management.hash_password", return_value="hashed-password")
    def test_creates_initial_admin_and_audit_in_one_transaction(self, _hash_password, transaction):
        connection, cursor = self._connection_with_rows(
            [{"acquired": 1}, {"count": 0}, None],
            lastrowid=10,
        )
        transaction.return_value.__enter__.return_value = connection

        user = create_initial_admin("Admin@Example.com", "관리자", "password-123", "test-cli")

        self.assertEqual(user["email"], "admin@example.com")
        self.assertEqual(user["role"], "admin")
        self.assertTrue(any("INSERT INTO users" in call.args[0] for call in cursor.execute.call_args_list))
        self.assertTrue(
            any("INSERT INTO admin_audit_logs" in call.args[0] for call in cursor.execute.call_args_list)
        )

    @patch("Back.app.services.admin_management.transaction")
    def test_initial_admin_creation_is_rejected_when_admin_exists(self, transaction):
        connection, _cursor = self._connection_with_rows([{"acquired": 1}, {"count": 1}])
        transaction.return_value.__enter__.return_value = connection

        with self.assertRaisesRegex(AdminManagementError, "이미 관리자 계정"):
            create_initial_admin("admin@example.com", "관리자", "password-123", "test-cli")

    @patch("Back.app.services.admin_management.transaction")
    def test_initial_admin_creation_requires_lock(self, transaction):
        connection, _cursor = self._connection_with_rows([{"acquired": 0}])
        transaction.return_value.__enter__.return_value = connection

        with self.assertRaisesRegex(AdminManagementError, "잠금을 획득"):
            create_initial_admin("admin@example.com", "관리자", "password-123", "test-cli")

    @patch("Back.app.services.admin_management.transaction")
    def test_promotes_active_user_and_records_audit(self, transaction):
        connection, cursor = self._connection_with_rows(
            [
                {
                    "id": 7,
                    "email": "user@example.com",
                    "display_name": "사용자",
                    "role": "user",
                    "status": "active",
                }
            ]
        )
        transaction.return_value.__enter__.return_value = connection

        user = promote_user_to_admin("user@example.com", "test-cli")

        self.assertEqual(user["role"], "admin")
        self.assertTrue(any("UPDATE users SET role" in call.args[0] for call in cursor.execute.call_args_list))
        self.assertTrue(
            any("INSERT INTO admin_audit_logs" in call.args[0] for call in cursor.execute.call_args_list)
        )

    @patch("Back.app.services.admin_management.transaction")
    def test_disabled_user_cannot_be_promoted(self, transaction):
        connection, _cursor = self._connection_with_rows(
            [
                {
                    "id": 7,
                    "email": "user@example.com",
                    "display_name": "사용자",
                    "role": "user",
                    "status": "disabled",
                }
            ]
        )
        transaction.return_value.__enter__.return_value = connection

        with self.assertRaisesRegex(AdminManagementError, "활성 상태"):
            promote_user_to_admin("user@example.com", "test-cli")

    @staticmethod
    def _connection_with_rows(rows, lastrowid=None):
        cursor = MagicMock()
        cursor.fetchone.side_effect = rows
        cursor.lastrowid = lastrowid
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        return connection, cursor


if __name__ == "__main__":
    unittest.main()
