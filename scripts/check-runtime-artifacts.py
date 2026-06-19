#!/usr/bin/env python3
# 런타임 JSON에 과거 절대 경로가 남아 있는지 찾고 목록만 출력한다.
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
STORAGE_DIR = ROOT_DIR / "Back" / "storage"
CHECK_DIRS = (
    STORAGE_DIR / "results",
    STORAGE_DIR / "practice_contexts",
)
LEGACY_PATH_MARKERS = (
    "/Back/uploads",
    "/Back/results",
    "/Back/frames",
    "/Back/models",
    "Back/uploads",
    "Back/results",
    "Back/frames",
    "Back/models",
)


def main():
    findings = []
    for directory in CHECK_DIRS:
        if not directory.exists():
            continue
        for path in sorted(directory.glob("*.json")):
            try:
                content = path.read_text(encoding="utf-8")
            except OSError as error:
                findings.append((path, f"read_error:{type(error).__name__}"))
                continue
            marker = next((item for item in LEGACY_PATH_MARKERS if item in content), None)
            if marker:
                findings.append((path, f"legacy_path:{marker}"))

    if not findings:
        print("런타임 산출물 점검 통과")
        return 0

    print("구 경로가 포함된 런타임 산출물이 있습니다. 자동 삭제하지 않습니다.", file=sys.stderr)
    for path, reason in findings:
        print(f"{path} {reason}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
