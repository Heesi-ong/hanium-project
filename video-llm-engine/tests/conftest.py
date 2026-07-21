import pytest


@pytest.fixture(autouse=True)
def _default_allowed_video_base_dir(monkeypatch, tmp_path_factory):
    # 대부분의 테스트가 pytest tmp_path 아래에 임시 비디오 파일을 만들어 videoPath로
    # 사용합니다. validate_local_video_path()의 기본 허용 경로(/storage)는 실제 배포
    # 환경(마운트된 공유 스토리지)을 반영한 값이라, 테스트에서는 pytest의 임시 디렉터리
    # 루트를 허용 경로로 바꿔줘야 기존 테스트들이 (수정 없이) 계속 통과합니다.
    monkeypatch.setenv(
        "VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR", str(tmp_path_factory.getbasetemp().parent)
    )
