import unittest
from unittest.mock import MagicMock, patch

from Back.app.services.admin_audit import delete_expired_admin_audit_logs


class AdminAuditTest(unittest.TestCase):
    @patch("Back.app.services.admin_audit.transaction")
    def test_expired_audit_logs_are_deleted_using_retention_policy(self, transaction):
        cursor = MagicMock()
        cursor.rowcount = 3
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        transaction.return_value.__enter__.return_value = connection

        deleted = delete_expired_admin_audit_logs()

        self.assertEqual(deleted, 3)
        self.assertEqual(cursor.execute.call_args.args[1], (365,))


if __name__ == "__main__":
    unittest.main()
