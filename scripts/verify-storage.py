#!/usr/bin/env python3
# 런타임 저장소 디렉터리와 모델 파일 배치 상태를 점검하는 CLI 스크립트다.
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.services.runtime_storage import (
    ensure_runtime_storage_dirs,
    get_model_files_status,
    get_runtime_storage_status,
)


def main():
    ensure_runtime_storage_dirs()
    storage = get_runtime_storage_status()
    models = get_model_files_status()

    for path in storage["missing"]:
        print(f"오류: 런타임 스토리지 디렉토리를 찾을 수 없습니다: {path}", file=sys.stderr)
    for path in models["missing"]:
        print(f"오류: 필수 분석 모델 파일을 찾을 수 없습니다: {path}", file=sys.stderr)

    if not storage["ok"] or not models["ok"]:
        return 1

    print("스토리지 검사 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
