import logging
import sys
from contextlib import contextmanager
from contextvars import ContextVar
from logging.handlers import RotatingFileHandler
from os import getenv
from pathlib import Path
from typing import Iterator

LOGGER_NAME = "analysis-engine"
LOG_FORMAT = "%(asctime)s %(levelname)s [%(threadName)s] [job_id=%(job_id)s] %(name)s - %(message)s"
LOG_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S%z"
MAX_LOG_BYTES = 100 * 1024 * 1024
BACKUP_COUNT = 10

# 영상 분석 요청(job) 하나의 로그를 다른 요청과 구분할 수 있도록 job_id를 구조화된 필드로
# 남깁니다. FastAPI가 동기 라우트 핸들러를 스레드풀에서 실행하고 그 스레드를 재사용하므로,
# bind_job_id로 요청이 끝나는 시점에 반드시 값을 되돌려야 다음 요청 로그에 이전 job_id가
# 남지 않습니다.
job_id_var: ContextVar[str] = ContextVar("job_id", default="-")


class JobIdLogFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.job_id = job_id_var.get()
        return True


@contextmanager
def bind_job_id(job_id: str) -> Iterator[None]:
    token = job_id_var.set(job_id)
    try:
        yield
    finally:
        job_id_var.reset(token)


def configure_logging() -> logging.Logger:
    logger = logging.getLogger(LOGGER_NAME)
    logger.setLevel(logging.INFO)
    logger.propagate = False

    if logger.handlers:
        return logger

    logger.addFilter(JobIdLogFilter())

    formatter = logging.Formatter(LOG_FORMAT, datefmt=LOG_DATE_FORMAT)

    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    log_dir = Path(getenv("LOG_DIR", "../storage/logs"))
    log_dir.mkdir(parents=True, exist_ok=True)

    file_handler = RotatingFileHandler(
        log_dir / "analysis-engine.log",
        maxBytes=MAX_LOG_BYTES,
        backupCount=BACKUP_COUNT,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

    return logger
