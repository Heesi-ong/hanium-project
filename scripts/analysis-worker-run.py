#!/usr/bin/env python3
# 백엔드와 분리해 분석 워커만 단독 실행하기 위한 진입점 스크립트다.
import signal
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.workers.analysis_worker import analysis_worker_manager


def main():
    stopping = False

    def stop(_signum, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    analysis_worker_manager.start()
    try:
        while not stopping:
            time.sleep(1)
    finally:
        analysis_worker_manager.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
