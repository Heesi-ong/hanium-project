"""환경변수와 런타임 저장 경로를 읽어 백엔드 전역 설정값으로 제공한다."""

import os
from pathlib import Path

from dotenv import load_dotenv

BACK_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BACK_DIR / ".env")

STORAGE_DIR = BACK_DIR / "storage"
UPLOAD_DIR = STORAGE_DIR / "uploads"
FRAME_DIR = STORAGE_DIR / "frames"
RESULT_DIR = STORAGE_DIR / "results"
MODEL_DIR = STORAGE_DIR / "models"
AI_COACHING_DIR = STORAGE_DIR / "ai_coaching"
PRACTICE_CONTEXT_DIR = STORAGE_DIR / "practice_contexts"
DELETION_STAGING_DIR = STORAGE_DIR / ".deletion_staging"
KNOWLEDGE_DIR = BACK_DIR / "knowledge"
RAG_MAX_DOCUMENTS = max(1, int(os.getenv("RAG_MAX_DOCUMENTS", "5")))
RAG_MAX_CHARS_PER_DOCUMENT = max(400, int(os.getenv("RAG_MAX_CHARS_PER_DOCUMENT", "1800")))

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
DB_MIGRATION_CONFIG = {
    **DB_CONFIG,
    "user": os.getenv("DB_MIGRATION_USER", "root"),
    "password": os.getenv("DB_MIGRATION_PASSWORD", ""),
}
DB_POOL_SIZE = max(1, int(os.getenv("DB_POOL_SIZE", "8")))
DB_POOL_TIMEOUT_SECONDS = max(0.5, float(os.getenv("DB_POOL_TIMEOUT_SECONDS", "5")))

SESSION_COOKIE_NAME = os.getenv("SESSION_COOKIE_NAME", "session_token")
SESSION_TTL_HOURS = int(os.getenv("SESSION_TTL_HOURS", "168"))
COOKIE_SECURE = os.getenv("COOKIE_SECURE", "false").lower() == "true"

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:4b")
OLLAMA_TIMEOUT_SECONDS = int(os.getenv("OLLAMA_TIMEOUT_SECONDS", "120"))
OLLAMA_NUM_CTX = max(1024, int(os.getenv("OLLAMA_NUM_CTX", "8192")))
OLLAMA_NUM_PREDICT = max(64, int(os.getenv("OLLAMA_NUM_PREDICT", "700")))
OLLAMA_CHAT_MAX_OUTPUT_TOKENS = max(128, int(os.getenv("OLLAMA_CHAT_MAX_OUTPUT_TOKENS", "700")))
AI_COACHING_MAX_OUTPUT_TOKENS = max(256, int(os.getenv("AI_COACHING_MAX_OUTPUT_TOKENS", "900")))
AI_COACHING_MAX_TRANSCRIPT_SEGMENTS = max(5, int(os.getenv("AI_COACHING_MAX_TRANSCRIPT_SEGMENTS", "12")))
AI_COACHING_MAX_SEGMENT_TEXT_CHARS = max(80, int(os.getenv("AI_COACHING_MAX_SEGMENT_TEXT_CHARS", "180")))
MAX_CHAT_CONTENT_CHARS = int(os.getenv("MAX_CHAT_CONTENT_CHARS", "8000"))
CHAT_HISTORY_MESSAGES = max(2, int(os.getenv("CHAT_HISTORY_MESSAGES", "20")))
CHAT_PENDING_TIMEOUT_MINUTES = max(1, int(os.getenv("CHAT_PENDING_TIMEOUT_MINUTES", "10")))
ANALYSIS_WORKERS = max(1, int(os.getenv("ANALYSIS_WORKERS", "1")))
ANALYSIS_POLL_SECONDS = max(0.2, float(os.getenv("ANALYSIS_POLL_SECONDS", "1")))
ANALYSIS_FRAME_INTERVAL_SECONDS = max(1, int(os.getenv("ANALYSIS_FRAME_INTERVAL_SECONDS", "5")))
ANALYSIS_SOURCE_RETENTION_HOURS = max(1, int(os.getenv("ANALYSIS_SOURCE_RETENTION_HOURS", "24")))
ANALYSIS_RESULT_RETENTION_DAYS = max(1, int(os.getenv("ANALYSIS_RESULT_RETENTION_DAYS", "90")))
ADMIN_AUDIT_RETENTION_DAYS = max(1, int(os.getenv("ADMIN_AUDIT_RETENTION_DAYS", "365")))
ORPHAN_FRAME_MIN_AGE_MINUTES = max(1, int(os.getenv("ORPHAN_FRAME_MIN_AGE_MINUTES", "10")))
MAINTENANCE_INTERVAL_SECONDS = max(60, int(os.getenv("MAINTENANCE_INTERVAL_SECONDS", "3600")))
MIN_FREE_DISK_MB = max(0, int(os.getenv("MIN_FREE_DISK_MB", "2048")))
OLLAMA_HEALTH_TIMEOUT_SECONDS = max(0.2, float(os.getenv("OLLAMA_HEALTH_TIMEOUT_SECONDS", "2")))
MAX_UPLOAD_MB = max(1, int(os.getenv("MAX_UPLOAD_MB", "500")))
USER_STORAGE_QUOTA_MB = max(MAX_UPLOAD_MB, int(os.getenv("USER_STORAGE_QUOTA_MB", "2048")))
USER_MAX_ACTIVE_ANALYSES = max(1, int(os.getenv("USER_MAX_ACTIVE_ANALYSES", "2")))
MAX_VIDEO_DURATION_SECONDS = max(1, int(os.getenv("MAX_VIDEO_DURATION_SECONDS", "1800")))
MAX_VIDEO_WIDTH = max(1, int(os.getenv("MAX_VIDEO_WIDTH", "3840")))
MAX_VIDEO_HEIGHT = max(1, int(os.getenv("MAX_VIDEO_HEIGHT", "2160")))
MAX_VIDEO_FPS = max(1, int(os.getenv("MAX_VIDEO_FPS", "60")))
MAX_VIDEO_FRAMES = max(1, int(os.getenv("MAX_VIDEO_FRAMES", "108000")))
MAX_EXTRACTED_FRAMES = max(1, int(os.getenv("MAX_EXTRACTED_FRAMES", "1800")))
MAX_FRAME_STORAGE_MB = max(1, int(os.getenv("MAX_FRAME_STORAGE_MB", "2048")))
WORKER_HEARTBEAT_STALE_SECONDS = max(5, int(os.getenv("WORKER_HEARTBEAT_STALE_SECONDS", "300")))
MAINTENANCE_STALE_SECONDS = max(
    MAINTENANCE_INTERVAL_SECONDS + 60,
    int(os.getenv("MAINTENANCE_STALE_SECONDS", str(MAINTENANCE_INTERVAL_SECONDS * 2 + 60))),
)
ACCOUNT_DELETION_WAIT_SECONDS = max(1, int(os.getenv("ACCOUNT_DELETION_WAIT_SECONDS", "30")))
ANALYSIS_ALGORITHM_VERSION = os.getenv("ANALYSIS_ALGORITHM_VERSION", "2026.06.3")
DISABLE_BACKGROUND_SERVICES = os.getenv("DISABLE_BACKGROUND_SERVICES", "false").lower() == "true"
