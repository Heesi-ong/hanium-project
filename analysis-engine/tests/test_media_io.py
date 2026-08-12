from pathlib import Path
from types import SimpleNamespace

import pytest

from app.services import media_io


class FakeCapture:
    def __init__(self, opened=True, read_error=None):
        self.opened = opened
        self.read_error = read_error
        self.released = False

    def isOpened(self):
        return self.opened

    def get(self, property_id):
        values = {
            media_io.cv2.CAP_PROP_FPS: 30.0,
            media_io.cv2.CAP_PROP_FRAME_COUNT: 60,
            media_io.cv2.CAP_PROP_FRAME_WIDTH: 1920,
            media_io.cv2.CAP_PROP_FRAME_HEIGHT: 1080,
        }
        return values[property_id]

    def set(self, property_id, value):
        return True

    def read(self):
        if self.read_error is not None:
            raise self.read_error
        return True, object()

    def release(self):
        self.released = True


@pytest.mark.parametrize("opened", [True, False])
def test_extract_video_info_always_releases_capture(tmp_path, monkeypatch, opened):
    video_path = tmp_path / "video.mp4"
    video_path.write_bytes(b"video")
    capture = FakeCapture(opened=opened)
    monkeypatch.setattr(media_io.cv2, "VideoCapture", lambda path: capture)

    result = media_io.extract_video_info(video_path)

    assert result["readable"] is opened
    assert capture.released is True


def test_extract_sample_frames_releases_capture_when_read_raises(tmp_path, monkeypatch):
    capture = FakeCapture(read_error=RuntimeError("decoder failed"))
    monkeypatch.setattr(media_io, "resolve_project_root", lambda: tmp_path)
    monkeypatch.setattr(media_io.cv2, "VideoCapture", lambda path: capture)

    with pytest.raises(RuntimeError, match="decoder failed"):
        media_io.extract_sample_frames("job-media", Path("video.mp4"), 30.0, 60)

    assert capture.released is True


def test_extract_audio_failure_preserves_existing_response_contract(tmp_path, monkeypatch):
    monkeypatch.setattr(media_io, "resolve_project_root", lambda: tmp_path)
    monkeypatch.setattr(media_io.imageio_ffmpeg, "get_ffmpeg_exe", lambda: "/usr/bin/ffmpeg")
    monkeypatch.setattr(
        media_io.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(returncode=1, stderr="decode failed"),
    )

    result = media_io.extract_audio_from_video("job-audio", Path("video.mp4"))

    assert result == {
        "success": False,
        "audioPath": "",
        "fileSize": 0,
        "sampleRate": 16000,
        "channelCount": 1,
        "codec": "pcm_s16le",
        "error": "decode failed",
    }
