from pathlib import Path


def resolve_project_root() -> Path:
    """analysis-engine을 실행 중인 위치와 무관하게(로컬 개발 시 analysis-engine/ 안에서
    바로 실행하든, Docker 컨테이너 안에서 WORKDIR=/app으로 실행하든) storage/ 디렉터리가
    있는 프로젝트 루트를 찾습니다. Docker 컨테이너에서는 storage가 /app의 형제
    디렉터리(/storage)로 마운트되므로, 파일 경로(__file__)가 아니라 현재 작업 디렉터리
    (Path.cwd())를 기준으로 상대 위치를 계산해야 정확히 찾을 수 있습니다.
    """
    current_path = Path.cwd().resolve()

    if current_path.name == "analysis-engine":
        return current_path.parent

    if (current_path / "storage").exists():
        return current_path

    if (current_path.parent / "storage").exists():
        return current_path.parent

    return current_path.parent
