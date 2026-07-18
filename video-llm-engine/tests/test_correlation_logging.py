import logging
from logging.handlers import TimedRotatingFileHandler

from app.core.logging_config import (
    CorrelationLogFilter,
    DailySizeRotatingFileHandler,
    bind_job_id,
    bind_request_id,
    configure_logging,
    job_id_var,
    request_id_var,
)


def make_record() -> logging.LogRecord:
    return logging.LogRecord(
        name="video-llm-engine",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="test message",
        args=(),
        exc_info=None,
    )


def test_correlation_filter_defaults_to_dash_outside_any_binding():
    record = make_record()

    assert CorrelationLogFilter().filter(record) is True
    assert record.job_id == "-"
    assert record.request_id == "-"


def test_bind_job_id_sets_and_resets_value():
    assert job_id_var.get() == "-"

    with bind_job_id("job-123"):
        assert job_id_var.get() == "job-123"

    assert job_id_var.get() == "-"


def test_bind_job_id_resets_value_even_when_body_raises():
    try:
        with bind_job_id("job-456"):
            assert job_id_var.get() == "job-456"
            raise RuntimeError("boom")
    except RuntimeError:
        pass

    assert job_id_var.get() == "-"


def test_bind_request_id_sets_and_resets_value():
    assert request_id_var.get() == "-"

    with bind_request_id("req-abc"):
        assert request_id_var.get() == "req-abc"

    assert request_id_var.get() == "-"


def test_bind_request_id_defaults_to_dash_when_header_missing():
    with bind_request_id(None):
        assert request_id_var.get() == "-"


def test_bind_job_id_and_request_id_nest_independently():
    with bind_job_id("job-outer"), bind_request_id("req-outer"):
        record = make_record()
        CorrelationLogFilter().filter(record)

        assert record.job_id == "job-outer"
        assert record.request_id == "req-outer"

    record_after = make_record()
    CorrelationLogFilter().filter(record_after)
    assert record_after.job_id == "-"
    assert record_after.request_id == "-"


def test_file_logging_rotates_daily_and_keeps_30_days(tmp_path, monkeypatch):
    monkeypatch.setenv("LOG_DIR", str(tmp_path))
    logger = logging.getLogger("video-llm-engine")
    original_handlers = list(logger.handlers)
    original_filters = list(logger.filters)
    logger.handlers.clear()
    logger.filters.clear()

    try:
        configured_logger = configure_logging()
        file_handlers = [
            handler for handler in configured_logger.handlers
            if isinstance(handler, TimedRotatingFileHandler)
        ]

        assert len(file_handlers) == 1
        assert file_handlers[0].when == "MIDNIGHT"
        assert file_handlers[0].backupCount == 30
        assert file_handlers[0].baseFilename.endswith("video-llm-engine.log")
        assert file_handlers[0].max_bytes > 0
    finally:
        for handler in logger.handlers:
            handler.close()
        logger.handlers[:] = original_handlers
        logger.filters[:] = original_filters


def test_daily_size_rotating_handler_rotates_before_midnight_when_size_exceeded(tmp_path):
    # 자정 회전만 있으면 크래시 루프처럼 짧은 시간에 로그가 몰릴 때 활성 파일이
    # 무한정 커질 수 있다. max_bytes를 작게 줘서, 자정이 되지 않아도 크기만으로
    # 회전이 실제로 발생하는지 확인한다.
    log_path = tmp_path / "size-test.log"
    handler = DailySizeRotatingFileHandler(
        log_path,
        when="midnight",
        backupCount=30,
        encoding="utf-8",
        max_bytes=200,
    )
    handler.setFormatter(logging.Formatter("%(message)s"))

    try:
        for index in range(20):
            record = logging.LogRecord(
                name="video-llm-engine",
                level=logging.INFO,
                pathname=__file__,
                lineno=1,
                msg=f"padding message number {index} to exceed the byte threshold",
                args=(),
                exc_info=None,
            )
            handler.handle(record)

        rotated_files = list(tmp_path.glob("size-test.log.*"))
        assert rotated_files, "expected at least one size-triggered rotation before midnight"
        assert log_path.stat().st_size < 200 * 5
    finally:
        handler.close()


def test_daily_size_rotating_handler_does_not_rotate_below_threshold(tmp_path):
    log_path = tmp_path / "size-test-small.log"
    handler = DailySizeRotatingFileHandler(
        log_path,
        when="midnight",
        backupCount=30,
        encoding="utf-8",
        max_bytes=1024 * 1024,
    )
    handler.setFormatter(logging.Formatter("%(message)s"))

    try:
        handler.handle(logging.LogRecord(
            name="video-llm-engine",
            level=logging.INFO,
            pathname=__file__,
            lineno=1,
            msg="small message",
            args=(),
            exc_info=None,
        ))

        assert list(tmp_path.glob("size-test-small.log.*")) == []
    finally:
        handler.close()
