import os
import tempfile
import unittest
from pathlib import Path

from Back.app.services.config_validation import validate_environment_file

BASE_ENV = """
ALLOWED_ORIGINS={origins}
DB_HOST=db.internal
DB_PORT=3306
DB_USER=app_user
DB_PASSWORD=app-password
DB_NAME=gpt_conversation_app
DB_MIGRATION_USER=migration_user
DB_MIGRATION_PASSWORD=migration-password
DB_BACKUP_USER=backup_user
DB_BACKUP_PASSWORD=backup-password
COOKIE_SECURE={cookie_secure}
OLLAMA_BASE_URL=http://127.0.0.1:11434
ANALYSIS_SOURCE_RETENTION_HOURS=24
ANALYSIS_RESULT_RETENTION_DAYS=90
ADMIN_AUDIT_RETENTION_DAYS=365
"""


class ConfigValidationTest(unittest.TestCase):
    def test_production_config_accepts_separated_accounts_and_https(self):
        result = self._validate(
            BASE_ENV.format(origins="https://speak.example.com", cookie_secure="true"),
            "production",
        )

        self.assertEqual(result["errors"], [])

    def test_production_config_rejects_local_origins_insecure_cookie_and_root(self):
        config = BASE_ENV.format(origins="http://127.0.0.1:5173", cookie_secure="false")
        config = config.replace("DB_MIGRATION_USER=migration_user", "DB_MIGRATION_USER=root")
        result = self._validate(config, "production")

        self.assertTrue(any("COOKIE_SECURE=true" in error for error in result["errors"]))
        self.assertTrue(any("로컬 주소" in error for error in result["errors"]))
        self.assertTrue(any("root 계정" in error for error in result["errors"]))

    def test_production_config_rejects_placeholder_passwords_and_shared_accounts(self):
        config = BASE_ENV.format(origins="https://speak.example.com", cookie_secure="true")
        config = config.replace("DB_PASSWORD=app-password", "DB_PASSWORD=change-this-password")
        config = config.replace("DB_MIGRATION_USER=migration_user", "DB_MIGRATION_USER=app_user")
        result = self._validate(config, "production")

        self.assertTrue(any("DB_PASSWORD" in error for error in result["errors"]))
        self.assertTrue(any("계정을 분리" in error for error in result["errors"]))

    def test_development_config_warns_without_rejecting_http_origin(self):
        result = self._validate(
            BASE_ENV.format(origins="http://127.0.0.1:5173", cookie_secure="false"),
            "development",
        )

        self.assertEqual(result["errors"], [])
        self.assertTrue(any("HTTP" in warning for warning in result["warnings"]))

    def test_retention_policy_must_match_confirmed_values(self):
        config = BASE_ENV.format(origins="https://speak.example.com", cookie_secure="true")
        config = config.replace("ANALYSIS_RESULT_RETENTION_DAYS=90", "ANALYSIS_RESULT_RETENTION_DAYS=30")

        result = self._validate(config, "production")

        self.assertTrue(any("ANALYSIS_RESULT_RETENTION_DAYS=90" in error for error in result["errors"]))

    @staticmethod
    def _validate(content, mode):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / ".env"
            path.write_text(content, encoding="utf-8")
            os.chmod(path, 0o600)
            return validate_environment_file(path, mode)


if __name__ == "__main__":
    unittest.main()
