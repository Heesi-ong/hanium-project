"""운영 로그에 남는 이메일, 경로, 식별자를 축약해 민감 정보 노출을 줄인다."""

from pathlib import Path


def safe_log_identifier(value, visible=8):
    text = str(value or "")
    if len(text) <= visible:
        return text
    return f"{text[:visible]}..."


def safe_log_path(value):
    if not value:
        return ""
    return Path(str(value)).name


def mask_email(value):
    text = str(value or "")
    local, separator, domain = text.partition("@")
    if not separator:
        return safe_log_identifier(text)
    if len(local) <= 2:
        masked_local = f"{local[:1]}*"
    else:
        masked_local = f"{local[:2]}***"
    return f"{masked_local}@{domain}"
