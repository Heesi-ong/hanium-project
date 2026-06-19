"""운영 배포 전에 환경변수 값의 보안·운영 조건을 검증한다."""

import ipaddress
import os
from pathlib import Path
from urllib.parse import urlparse

from dotenv import dotenv_values

PLACEHOLDER_VALUES = {
    "",
    "change-me",
    "change-this-password",
    "password",
    "secret",
}


def validate_environment_file(env_path, mode="development"):
    path = Path(env_path)
    values = {key: str(value or "").strip() for key, value in dotenv_values(path).items()}
    errors = []
    warnings = []

    if mode not in {"development", "production"}:
        return {"mode": mode, "path": str(path), "errors": ["지원하지 않는 검증 모드입니다."], "warnings": []}
    if not path.is_file():
        return {"mode": mode, "path": str(path), "errors": ["환경변수 파일을 찾을 수 없습니다."], "warnings": []}

    _check_required(values, errors)
    _check_origins(values.get("ALLOWED_ORIGINS", ""), mode, errors, warnings)
    _check_boolean(values, "COOKIE_SECURE", errors)
    _check_database_accounts(values, mode, errors, warnings)
    _check_ollama_url(values.get("OLLAMA_BASE_URL", ""), errors, warnings)
    _check_retention_policy(values, errors)
    _check_env_permissions(path, mode, errors, warnings)

    if mode == "production":
        if values.get("COOKIE_SECURE", "").lower() != "true":
            errors.append("운영 환경에서는 COOKIE_SECURE=true가 필요합니다.")
        if values.get("DB_HOST") in {"127.0.0.1", "localhost"}:
            warnings.append("DB_HOST가 로컬 주소입니다. 단일 서버 운영인지 확인하세요.")

    return {
        "mode": mode,
        "path": str(path),
        "errors": errors,
        "warnings": warnings,
    }


def _check_required(values, errors):
    for key in ("ALLOWED_ORIGINS", "DB_HOST", "DB_PORT", "DB_USER", "DB_NAME", "OLLAMA_BASE_URL"):
        if not values.get(key):
            errors.append(f"{key} 값이 필요합니다.")
    try:
        port = int(values.get("DB_PORT", ""))
        if not 1 <= port <= 65535:
            raise ValueError
    except ValueError:
        errors.append("DB_PORT는 1~65535 사이의 정수여야 합니다.")


def _check_origins(raw_origins, mode, errors, warnings):
    origins = [origin.strip() for origin in raw_origins.split(",") if origin.strip()]
    if not origins:
        return
    for origin in origins:
        parsed = urlparse(origin)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path not in {"", "/"}:
            errors.append(f"ALLOWED_ORIGINS 항목 형식이 올바르지 않습니다: {origin}")
            continue
        if "*" in origin:
            errors.append("ALLOWED_ORIGINS에는 와일드카드를 사용할 수 없습니다.")
        if mode == "production":
            if parsed.scheme != "https":
                errors.append(f"운영 허용 출처는 HTTPS여야 합니다: {origin}")
            if _is_loopback_host(parsed.hostname):
                errors.append(f"운영 허용 출처에 로컬 주소를 사용할 수 없습니다: {origin}")
        elif parsed.scheme != "https":
            warnings.append(f"개발 허용 출처가 HTTP입니다: {origin}")


def _check_boolean(values, key, errors):
    if values.get(key, "").lower() not in {"true", "false"}:
        errors.append(f"{key}는 true 또는 false여야 합니다.")


def _check_database_accounts(values, mode, errors, warnings):
    runtime_user = values.get("DB_USER", "")
    migration_user = values.get("DB_MIGRATION_USER", "")
    backup_user = values.get("DB_BACKUP_USER", "")

    for key in ("DB_PASSWORD", "DB_MIGRATION_PASSWORD", "DB_BACKUP_PASSWORD"):
        value = values.get(key, "")
        if mode == "production" and value.casefold() in PLACEHOLDER_VALUES:
            errors.append(f"운영 환경에서는 {key}에 비어 있지 않은 전용 비밀번호가 필요합니다.")

    if mode == "production":
        for key, user in (
            ("DB_USER", runtime_user),
            ("DB_MIGRATION_USER", migration_user),
            ("DB_BACKUP_USER", backup_user),
        ):
            if user.casefold() == "root":
                errors.append(f"운영 환경에서는 {key}에 root 계정을 사용할 수 없습니다.")
        if len({runtime_user, migration_user, backup_user}) < 3:
            errors.append("운영 환경에서는 애플리케이션·마이그레이션·백업 DB 계정을 분리해야 합니다.")
    elif runtime_user and runtime_user in {migration_user, backup_user}:
        warnings.append("애플리케이션 DB 계정이 관리용 계정과 동일합니다.")


def _check_ollama_url(raw_url, errors, warnings):
    parsed = urlparse(raw_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        errors.append("OLLAMA_BASE_URL은 유효한 HTTP 또는 HTTPS URL이어야 합니다.")
        return
    if not _is_loopback_host(parsed.hostname):
        warnings.append("OLLAMA_BASE_URL이 로컬 주소가 아닙니다. 외부 노출 및 네트워크 접근 정책을 확인하세요.")


def _check_retention_policy(values, errors):
    for key, expected in (
        ("ANALYSIS_SOURCE_RETENTION_HOURS", 24),
        ("ANALYSIS_RESULT_RETENTION_DAYS", 90),
        ("ADMIN_AUDIT_RETENTION_DAYS", 365),
    ):
        try:
            actual = int(values.get(key, ""))
        except ValueError:
            errors.append(f"{key}는 정수여야 합니다.")
            continue
        if actual != expected:
            errors.append(f"확정된 보존 정책에 따라 {key}={expected}가 필요합니다.")


def _check_env_permissions(path, mode, errors, warnings):
    if os.name == "nt" or path.name.endswith(".example"):
        return
    permissions = path.stat().st_mode & 0o777
    if permissions & 0o077:
        message = f"{path.name} 파일 권한은 600을 권장합니다. 현재 권한: {permissions:o}"
        (errors if mode == "production" else warnings).append(message)


def _is_loopback_host(host):
    if not host:
        return False
    if host.casefold() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False
