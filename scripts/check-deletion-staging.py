#!/usr/bin/env python3
# 삭제 격리 영역에 미커밋 또는 오래된 항목이 남아 있는지 점검한다.
import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.services.deletion_staging import COMMITTED_MARKER, DELETION_STAGING_DIR


def inspect_staging(root=DELETION_STAGING_DIR, now=None):
    current_time = time.time() if now is None else now
    results = []
    if not root.exists():
        return results
    for path in sorted(root.iterdir()):
        if not path.is_dir():
            continue
        results.append(
            {
                "name": path.name,
                "committed": (path / COMMITTED_MARKER).exists(),
                "age_seconds": max(0, int(current_time - path.stat().st_mtime)),
                "entry_count": sum(1 for item in path.iterdir() if item.name != COMMITTED_MARKER),
            }
        )
    return results


def main():
    parser = argparse.ArgumentParser(description="삭제 격리 디렉터리를 읽기 전용으로 점검합니다.")
    parser.add_argument("--fail-on-uncommitted", action="store_true")
    args = parser.parse_args()
    results = inspect_staging()
    if not results:
        print("삭제 격리 디렉터리가 비어 있습니다.")
        return 0
    for item in results:
        state = "committed" if item["committed"] else "UNCOMMITTED"
        print(f"{item['name']} state={state} age_seconds={item['age_seconds']} entries={item['entry_count']}")
    if args.fail_on_uncommitted and any(not item["committed"] for item in results):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
