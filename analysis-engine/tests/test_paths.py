from app.core.paths import resolve_project_root


def test_resolve_project_root_finds_sibling_storage_dir_in_container_layout(monkeypatch, tmp_path):
    # Docker 컨테이너의 실제 레이아웃을 흉내냅니다: WORKDIR=/app(소스 코드),
    # storage는 /app의 형제 디렉터리(/storage)로 마운트됩니다. cwd가 "analysis-engine"이
    # 아니고, cwd 바로 아래에도 storage가 없고, cwd의 부모 아래에 storage가 있는 경우입니다.
    app_dir = tmp_path / "app"
    app_dir.mkdir()
    (tmp_path / "storage").mkdir()

    monkeypatch.chdir(app_dir)

    assert resolve_project_root() == tmp_path


def test_resolve_project_root_returns_parent_when_cwd_is_analysis_engine(monkeypatch, tmp_path):
    # 로컬 개발 시 analysis-engine/ 디렉터리 안에서 바로 uvicorn을 실행하는 경우입니다.
    project_root = tmp_path / "hanium-project"
    analysis_engine_dir = project_root / "analysis-engine"
    analysis_engine_dir.mkdir(parents=True)
    (project_root / "storage").mkdir()

    monkeypatch.chdir(analysis_engine_dir)

    assert resolve_project_root() == project_root


def test_resolve_project_root_returns_cwd_when_storage_is_directly_inside(monkeypatch, tmp_path):
    (tmp_path / "storage").mkdir()

    monkeypatch.chdir(tmp_path)

    assert resolve_project_root() == tmp_path
