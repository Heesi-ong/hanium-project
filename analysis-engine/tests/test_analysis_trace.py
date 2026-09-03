from app.services.analysis_trace import AnalysisTrace


def test_trace_records_steps_in_order_with_labels_and_total():
    trace = AnalysisTrace(total_steps=3)

    trace.step(1, "a", "첫 단계")
    trace.step(2, "b", "둘째 단계")
    trace.finish()

    entries = trace.to_list()
    assert [entry["stepNo"] for entry in entries] == [1, 2]
    assert [entry["key"] for entry in entries] == ["a", "b"]
    assert all(entry["totalSteps"] == 3 for entry in entries)
    assert all(entry["startedAtIso"] for entry in entries)


def test_detail_attaches_to_current_step_only():
    trace = AnalysisTrace(total_steps=2)

    trace.step(1, "a", "첫 단계")
    trace.detail("프레임 20장 추출")
    trace.step(2, "b", "둘째 단계")
    trace.finish()

    entries = trace.to_list()
    assert entries[0]["detail"] == "프레임 20장 추출"
    assert entries[1]["detail"] is None


def test_each_step_gets_non_negative_duration_once_closed():
    trace = AnalysisTrace(total_steps=2)

    trace.step(1, "a", "첫 단계")
    trace.step(2, "b", "둘째 단계")
    trace.finish()

    for entry in trace.to_list():
        assert isinstance(entry["durationMs"], float)
        assert entry["durationMs"] >= 0.0


def test_to_list_does_not_leak_internal_timing_keys():
    trace = AnalysisTrace(total_steps=1)
    trace.step(1, "a", "첫 단계")
    trace.finish()

    entry = trace.to_list()[0]
    assert set(entry) == {
        "stepNo",
        "totalSteps",
        "key",
        "label",
        "startedAtIso",
        "durationMs",
        "detail",
    }
