import hashlib
import re
from pathlib import Path

from ..config import DB_MIGRATION_CONFIG
from ..services.database import transaction

MIGRATIONS_DIR = Path(__file__).resolve().parents[2] / "migrations"
MIGRATION_PATTERN = re.compile(r"^\d{3}_[a-z0-9_]+\.sql$")
LEGACY_CODE_VERSIONS = {
    "004_operational_indexes": hashlib.sha256(b"004_operational_indexes").hexdigest(),
    "005_analysis_idempotency": hashlib.sha256(b"005_analysis_idempotency").hexdigest(),
}


def _checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _discover_migrations():
    return sorted(path for path in MIGRATIONS_DIR.iterdir() if MIGRATION_PATTERN.fullmatch(path.name))


def _execute_sql_file(cursor, path):
    statements = [statement.strip() for statement in path.read_text().split(";") if statement.strip()]
    for statement in statements:
        if statement.upper().startswith("USE "):
            continue
        cursor.execute(statement)


def apply_migrations():
    applied = []
    migrations = _discover_migrations()
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
            cursor.execute("SELECT version, checksum FROM schema_migrations")
            existing = {row["version"]: row["checksum"] for row in cursor.fetchall()}

            for path in migrations:
                version = path.name
                checksum = _checksum(path)
                stored = existing.get(version)
                legacy_version = path.stem if path.stem in LEGACY_CODE_VERSIONS else None
                legacy_checksum = existing.get(legacy_version) if legacy_version else None

                if stored:
                    if stored != checksum:
                        raise RuntimeError(f"Migration checksum mismatch: {version}")
                    continue
                if legacy_version and legacy_checksum:
                    if legacy_checksum != LEGACY_CODE_VERSIONS[legacy_version]:
                        raise RuntimeError(f"Migration checksum mismatch: {legacy_version}")
                    cursor.execute(
                        "INSERT INTO schema_migrations (version, checksum) VALUES (%s, %s)",
                        (version, checksum),
                    )
                    applied.append(f"{version} (legacy recorded)")
                    continue

                _execute_sql_file(cursor, path)
                cursor.execute(
                    "INSERT INTO schema_migrations (version, checksum) VALUES (%s, %s)",
                    (version, checksum),
                )
                applied.append(version)
    return applied
