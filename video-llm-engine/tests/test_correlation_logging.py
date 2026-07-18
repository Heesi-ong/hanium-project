import logging

from app.core.logging_config import (
    CorrelationLogFilter,
    bind_job_id,
    bind_request_id,
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
