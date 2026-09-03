import json

from app.services import progress_file


def test_write_basic_progress_creates_atomic_json_file(tmp_path, monkeypatch):
    monkeypatch.setattr(progress_file, "resolve_project_root", lambda: tmp_path)

    progress_file.write_basic_progress(
        "20260101000000-abcdef12", 5, 9, "pose_gesture", "자세와 제스처를 분석하는 중..."
    )

    target = tmp_path / "storage" / "temp" / "20260101000000-abcdef12" / "progress.json"
    assert target.exists()
    assert not (target.parent / "progress.json.tmp").exists()

    payload = json.loads(target.read_text(encoding="utf-8"))
    assert payload["phase"] == "BASIC_ANALYSIS"
    assert payload["stepNo"] == 5
    assert payload["totalSteps"] == 9
    assert payload["stepKey"] == "pose_gesture"
    assert payload["label"].startswith("자세와 제스처")
    assert payload["updatedAtIso"]


def test_write_basic_progress_overwrites_previous_step(tmp_path, monkeypatch):
    monkeypatch.setattr(progress_file, "resolve_project_root", lambda: tmp_path)

    progress_file.write_basic_progress("20260101000000-abcdef12", 2, 9, "frame_extract", "프레임 추출 중")
    progress_file.write_basic_progress("20260101000000-abcdef12", 7, 9, "speech_metrics", "말속도 분석 중")

    target = tmp_path / "storage" / "temp" / "20260101000000-abcdef12" / "progress.json"
    payload = json.loads(target.read_text(encoding="utf-8"))
    assert payload["stepNo"] == 7
    assert payload["stepKey"] == "speech_metrics"


def test_write_basic_progress_never_raises(monkeypatch):
    def _boom():
        raise RuntimeError("no storage")

    monkeypatch.setattr(progress_file, "resolve_project_root", _boom)

    # 진행률 기록 실패는 분석을 막지 않아야 합니다.
    progress_file.write_basic_progress("20260101000000-abcdef12", 1, 9, "video_check", "확인 중")
