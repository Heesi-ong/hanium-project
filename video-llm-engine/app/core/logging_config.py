import logging
import sys
from logging.handlers import RotatingFileHandler
from os import getenv
from pathlib import Path

LOGGER_NAME = "video-llm-engine"
LOG_FORMAT = "%(asctime)s %(levelname)s [%(threadName)s] %(name)s - %(message)s"
LOG_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S%z"
MAX_LOG_BYTES = 100 * 1024 * 1024
BACKUP_COUNT = 10


def configure_logging() -> logging.Logger:
    logger = logging.getLogger(LOGGER_NAME)
    logger.setLevel(logging.INFO)
    logger.propagate = False

    if logger.handlers:
        return logger

    formatter = logging.Formatter(LOG_FORMAT, datefmt=LOG_DATE_FORMAT)

    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    log_dir = Path(getenv("LOG_DIR", "../storage/logs"))
    log_dir.mkdir(parents=True, exist_ok=True)

    file_handler = RotatingFileHandler(
        log_dir / "video-llm-engine.log",
        maxBytes=MAX_LOG_BYTES,
        backupCount=BACKUP_COUNT,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

    return logger
