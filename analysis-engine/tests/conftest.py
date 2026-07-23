import pytest

from app.core.settings import clear_settings


@pytest.fixture(autouse=True)
def _reset_engine_settings():
    clear_settings()
    yield
    clear_settings()


@pytest.fixture(autouse=True)
def _default_allowed_video_base_dir(monkeypatch, tmp_path_factory):
    # 대부분의 테스트가 pytest tmp_path 아래에 임시 비디오 파일을 만들어 videoPath로
    # 사용합니다. validate_local_video_path()의 기본 허용 경로(/storage)는 실제 배포
    # 환경(마운트된 공유 스토리지)을 반영한 값이라, 테스트에서는 pytest의 임시 디렉터리
    # 루트를 허용 경로로 바꿔줘야 기존 테스트들이 (수정 없이) 계속 통과합니다.
    # (video-llm-engine/tests/conftest.py와 동일한 이유/패턴)
    monkeypatch.setenv(
        "ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR", str(tmp_path_factory.getbasetemp().parent)
    )


@pytest.fixture(autouse=True)
def _default_allowed_download_hosts(monkeypatch):
    # 기존 테스트들이 실제 MinIO 호스트(minio:9000) 대신 예시 호스트(minio.local)로
    # videoDownloadUrl을 구성하므로, 허용 목록에 포함시켜 기존 테스트가 수정 없이
    # 계속 통과하게 합니다. 허용 목록 자체를 검증하는 테스트는 이 값을 다시 좁혀 씁니다.
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "minio.local:443,minio:9000")
