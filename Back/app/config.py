import os
from pathlib import Path

from dotenv import load_dotenv

BACK_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BACK_DIR / ".env")

UPLOAD_DIR = BACK_DIR / "uploads"
FRAME_DIR = BACK_DIR / "frames"
RESULT_DIR = BACK_DIR / "results"
MODEL_DIR = BACK_DIR / "models"

ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.getenv(
        "ALLOWED_ORIGINS",
        "http://localhost:5173,http://127.0.0.1:5173"
    ).split(",")
    if origin.strip()
]

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "127.0.0.1"),
    "port": int(os.getenv("DB_PORT", "3307")),
    "user": os.getenv("DB_USER", "gpt_app"),
    "password": os.getenv("DB_PASSWORD", ""),
    "database": os.getenv("DB_NAME", "gpt_conversation_app"),
}

SESSION_COOKIE_NAME = os.getenv("SESSION_COOKIE_NAME", "session_token")
SESSION_TTL_HOURS = int(os.getenv("SESSION_TTL_HOURS", "168"))
COOKIE_SECURE = os.getenv("COOKIE_SECURE", "false").lower() == "true"

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:4b")
OLLAMA_TIMEOUT_SECONDS = int(os.getenv("OLLAMA_TIMEOUT_SECONDS", "120"))
MAX_CHAT_CONTENT_CHARS = int(os.getenv("MAX_CHAT_CONTENT_CHARS", "8000"))
CHAT_HISTORY_MESSAGES = max(2, int(os.getenv("CHAT_HISTORY_MESSAGES", "20")))
CHAT_PENDING_TIMEOUT_MINUTES = max(1, int(os.getenv("CHAT_PENDING_TIMEOUT_MINUTES", "10")))
ANALYSIS_WORKERS = max(1, int(os.getenv("ANALYSIS_WORKERS", "1")))
ANALYSIS_POLL_SECONDS = max(0.2, float(os.getenv("ANALYSIS_POLL_SECONDS", "1")))
ANALYSIS_SOURCE_RETENTION_HOURS = max(1, int(os.getenv("ANALYSIS_SOURCE_RETENTION_HOURS", "24")))
ANALYSIS_RESULT_RETENTION_DAYS = max(0, int(os.getenv("ANALYSIS_RESULT_RETENTION_DAYS", "0")))
ORPHAN_FRAME_MIN_AGE_MINUTES = max(1, int(os.getenv("ORPHAN_FRAME_MIN_AGE_MINUTES", "10")))
MAINTENANCE_INTERVAL_SECONDS = max(60, int(os.getenv("MAINTENANCE_INTERVAL_SECONDS", "3600")))
MIN_FREE_DISK_MB = max(0, int(os.getenv("MIN_FREE_DISK_MB", "2048")))
OLLAMA_HEALTH_TIMEOUT_SECONDS = max(0.2, float(os.getenv("OLLAMA_HEALTH_TIMEOUT_SECONDS", "2")))
