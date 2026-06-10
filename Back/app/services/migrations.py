import hashlib
from pathlib import Path

from ..config import DB_MIGRATION_CONFIG
from ..services.database import transaction

MIGRATIONS_DIR = Path(__file__).resolve().parents[2] / "migrations"
LEGACY_MIGRATIONS = ("001_auth_chat_schema.sql", "002_analysis_jobs.sql", "003_analysis_queue_progress.sql")


def _checksum(filename):
    return hashlib.sha256((MIGRATIONS_DIR / filename).read_bytes()).hexdigest()


def _table_exists(cursor, table_name):
    cursor.execute(
        "SELECT COUNT(*) AS count FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = %s",
        (table_name,),
    )
    return cursor.fetchone()["count"] == 1


def _column_exists(cursor, table_name, column_name):
    cursor.execute(
        """
        SELECT COUNT(*) AS count FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = %s AND column_name = %s
        """,
        (table_name, column_name),
    )
    return cursor.fetchone()["count"] == 1


def _index_exists(cursor, table_name, index_name):
    cursor.execute(
        """
        SELECT COUNT(*) AS count FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = %s AND index_name = %s
        """,
        (table_name, index_name),
    )
    return cursor.fetchone()["count"] > 0


def apply_migrations():
    applied = []
    with transaction(DB_MIGRATION_CONFIG) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                  version VARCHAR(100) NOT NULL,
                  checksum CHAR(64) NOT NULL,
                  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (version)
                )
                """
            )
            legacy_ready = (
                _table_exists(cursor, "users")
                and _table_exists(cursor, "analysis_jobs")
                and _column_exists(cursor, "analysis_jobs", "last_heartbeat_at")
            )
            if not legacy_ready:
                raise RuntimeError("Legacy schema is incomplete. Apply migrations 001-003 before running this command.")

            for filename in LEGACY_MIGRATIONS:
                cursor.execute(
                    "INSERT IGNORE INTO schema_migrations (version, checksum) VALUES (%s, %s)",
                    (filename, _checksum(filename)),
                )
                if cursor.rowcount:
                    applied.append(filename)

            indexes = (
                ("analysis_jobs", "idx_analysis_jobs_user_status_created", "(user_id, status, created_at)"),
                ("conversations", "idx_conversations_user_archived_updated", "(user_id, archived_at, updated_at)"),
                ("gpt_usage", "uq_gpt_usage_request_id", "(request_id)"),
            )
            for table_name, index_name, columns in indexes:
                if not _index_exists(cursor, table_name, index_name):
                    cursor.execute(f"ALTER TABLE {table_name} ADD {'UNIQUE ' if index_name.startswith('uq_') else ''}INDEX {index_name} {columns}")

            version = "004_operational_indexes"
            cursor.execute(
                "INSERT IGNORE INTO schema_migrations (version, checksum) VALUES (%s, %s)",
                (version, hashlib.sha256(version.encode()).hexdigest()),
            )
            if cursor.rowcount:
                applied.append(version)

            if not _column_exists(cursor, "analysis_jobs", "idempotency_key"):
                cursor.execute("ALTER TABLE analysis_jobs ADD COLUMN idempotency_key VARCHAR(255) NULL AFTER user_id")
            if not _index_exists(cursor, "analysis_jobs", "uq_analysis_jobs_user_idempotency"):
                cursor.execute(
                    "ALTER TABLE analysis_jobs ADD UNIQUE INDEX uq_analysis_jobs_user_idempotency "
                    "(user_id, idempotency_key)"
                )
            version = "005_analysis_idempotency"
            cursor.execute(
                "INSERT IGNORE INTO schema_migrations (version, checksum) VALUES (%s, %s)",
                (version, hashlib.sha256(version.encode()).hexdigest()),
            )
            if cursor.rowcount:
                applied.append(version)
    return applied
