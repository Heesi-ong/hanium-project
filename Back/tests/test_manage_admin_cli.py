import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

from Back.app.services.admin_management import AdminManagementError

SCRIPT_PATH = Path(__file__).resolve().parents[2] / "scripts" / "manage-admin.py"
SPEC = importlib.util.spec_from_file_location("manage_admin_cli", SCRIPT_PATH)
manage_admin_cli = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manage_admin_cli)


class ManageAdminCliTest(unittest.TestCase):
    @patch("builtins.input", return_value="different@example.com")
    def test_target_email_must_be_reentered_exactly(self, _input):
        with self.assertRaisesRegex(AdminManagementError, "일치하지 않아"):
            manage_admin_cli._confirm_target("admin@example.com")

    @patch("builtins.input", return_value="ADMIN@EXAMPLE.COM")
    def test_target_email_confirmation_is_case_insensitive(self, _input):
        manage_admin_cli._confirm_target("admin@example.com")


if __name__ == "__main__":
    unittest.main()
