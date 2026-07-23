import pytest

from app.core import network_security


def test_validate_video_download_url_accepts_allowed_host(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    network_security.validate_video_download_url("http://minio:9000/uploads/job/original.mp4")


def test_validate_video_download_url_rejects_host_outside_allowlist(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    with pytest.raises(ValueError, match="not in ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS"):
        network_security.validate_video_download_url("http://attacker.example.com/steal.mp4")


def test_validate_video_download_url_rejects_internal_metadata_endpoint(monkeypatch):
    # 클라우드 메타데이터 엔드포인트(169.254.169.254)로 향하는 SSRF 시도를 막는다.
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    with pytest.raises(ValueError, match="not in ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS"):
        network_security.validate_video_download_url("http://169.254.169.254/latest/meta-data/")


def test_validate_video_download_url_rejects_non_http_scheme(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    with pytest.raises(ValueError, match="must be an absolute http\\(s\\) URL"):
        network_security.validate_video_download_url("file:///etc/passwd")


def test_validate_video_download_url_matches_host_and_port_case_insensitively(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "MinIO:9000")

    network_security.validate_video_download_url("http://minio:9000/uploads/job/original.mp4")


def test_validate_video_download_url_uses_default_scheme_port_when_not_explicit(monkeypatch):
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "minio:9000")

    # https 스킴에는 443이 기본 포트이므로, 명시적 포트가 없는 https://minio:9000은
    # 이 허용 목록(9000)과 일치하지 않아야 한다(포트가 다르면 다른 서비스일 수 있다).
    with pytest.raises(ValueError, match="not in ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS"):
        network_security.validate_video_download_url("https://minio/uploads/job/original.mp4")


def test_resolve_allowed_download_hosts_rejects_empty_allowlist(monkeypatch):
    # 빈 값(또는 공백/쉼표만)으로 설정되면 모든 videoDownloadUrl이 조용히 거절되므로,
    # 요청이 들어올 때마다 실패하는 대신 값을 읽는 시점에 바로 드러낸다.
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS", "  , ,")

    with pytest.raises(RuntimeError, match="ANALYSIS_ENGINE_ALLOWED_DOWNLOAD_HOSTS"):
        network_security.resolve_allowed_download_hosts()


def test_validate_local_video_path_accepts_path_inside_allowed_base_dir(monkeypatch, tmp_path):
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR", str(tmp_path))
    video_path = tmp_path / "uploads" / "job" / "original.mp4"
    video_path.parent.mkdir(parents=True)
    video_path.write_bytes(b"fake")

    network_security.validate_local_video_path(video_path)


def test_validate_local_video_path_rejects_path_outside_allowed_base_dir(monkeypatch, tmp_path):
    allowed_dir = tmp_path / "allowed"
    allowed_dir.mkdir()
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR", str(allowed_dir))

    outside_path = tmp_path / "outside" / "secret.mp4"
    outside_path.parent.mkdir()
    outside_path.write_bytes(b"fake")

    with pytest.raises(ValueError, match="must be inside"):
        network_security.validate_local_video_path(outside_path)


def test_validate_local_video_path_rejects_path_traversal_out_of_allowed_base_dir(
    monkeypatch, tmp_path
):
    allowed_dir = tmp_path / "allowed"
    allowed_dir.mkdir()
    monkeypatch.setenv("ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR", str(allowed_dir))

    traversal_path = allowed_dir / ".." / "outside" / "secret.mp4"

    with pytest.raises(ValueError, match="must be inside"):
        network_security.validate_local_video_path(traversal_path)
