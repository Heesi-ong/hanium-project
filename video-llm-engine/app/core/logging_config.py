import logging
import sys
from contextlib import contextmanager
from contextvars import ContextVar
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path
from typing import Iterator

from app.core.settings import get_settings

LOGGER_NAME = "video-llm-engine"
LOG_FORMAT = (
    "%(asctime)s %(levelname)s [%(threadName)s] "
    "[job_id=%(job_id)s] [request_id=%(request_id)s] %(name)s - %(message)s"
)
LOG_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S%z"
LOG_RETENTION_DAYS = 30
# 자정 기준 일 단위 회전만으로는 크래시 루프 등으로 짧은 시간에 로그가 폭증할 때
# 활성 로그 파일 크기에 상한이 없다(backend의 SizeAndTimeBasedRollingPolicy는 시간과
# 크기를 함께 본다). DailySizeRotatingFileHandler가 자정 전에도 이 크기를 넘기면
# 먼저 회전시켜 디스크 사용량을 억제한다.
LOG_MAX_BYTES = 100 * 1024 * 1024


class DailySizeRotatingFileHandler(TimedRotatingFileHandler):
    def __init__(self, *args, max_bytes: int, **kwargs):
        super().__init__(*args, **kwargs)
        self.max_bytes = max_bytes

    def shouldRollover(self, record: logging.LogRecord) -> int:
        if super().shouldRollover(record):
            return 1

        if self.max_bytes <= 0:
            return 0

        if self.stream is None:
            self.stream = self._open()

        message = self.format(record) + "\n"
        self.stream.seek(0, 2)
        return 1 if self.stream.tell() + len(message) >= self.max_bytes else 0

# 영상 분석 요청(job) 하나, 그리고 그 요청을 보낸 backend의 HTTP 요청(request_id)을
# 다른 요청과 구분할 수 있도록 구조화된 필드로 남깁니다. request_id는 backend가
# X-Request-Id 헤더로 전달하며, backend 로그의 requestId와 값이 같아 두 서비스 로그를
# 같은 ID로 이어서 추적할 수 있습니다. FastAPI가 동기 라우트 핸들러를 스레드풀에서
# 실행하고 그 스레드를 재사용하므로, bind_job_id/bind_request_id로 요청이 끝나는
# 시점에 반드시 값을 되돌려야 다음 요청 로그에 이전 값이 남지 않습니다.
job_id_var: ContextVar[str] = ContextVar("job_id", default="-")
request_id_var: ContextVar[str] = ContextVar("request_id", default="-")


class CorrelationLogFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.job_id = job_id_var.get()
        record.request_id = request_id_var.get()
        return True


@contextmanager
def bind_job_id(job_id: str) -> Iterator[None]:
    token = job_id_var.set(job_id)
    try:
        yield
    finally:
        job_id_var.reset(token)


@contextmanager
def bind_request_id(request_id: str | None) -> Iterator[None]:
    token = request_id_var.set(request_id if request_id else "-")
    try:
        yield
    finally:
        request_id_var.reset(token)


def configure_logging(log_dir: Path | None = None) -> logging.Logger:
    logger = logging.getLogger(LOGGER_NAME)
    logger.setLevel(logging.INFO)
    logger.propagate = False

    if logger.handlers:
        return logger

    logger.addFilter(CorrelationLogFilter())

    formatter = logging.Formatter(LOG_FORMAT, datefmt=LOG_DATE_FORMAT)

    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    resolved_log_dir = log_dir or get_settings().log_dir
    resolved_log_dir.mkdir(parents=True, exist_ok=True)

    file_handler = DailySizeRotatingFileHandler(
        resolved_log_dir / "video-llm-engine.log",
        when="midnight",
        backupCount=LOG_RETENTION_DAYS,
        encoding="utf-8",
        max_bytes=LOG_MAX_BYTES,
    )
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

    return logger
