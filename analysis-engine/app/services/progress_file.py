"""기본 분석의 세부 진행 상태를 ``storage/temp/{job_id}/progress.json`` 에 기록합니다.

백엔드는 분석 엔진을 한 번의 동기 HTTP 호출로 기다리기 때문에, 엔진이 내부적으로
9단계를 도는 "동안"의 진행률을 알 방법이 없습니다. 엔진이 이 파일에 현재 단계를 쓰고
백엔드가 ``BASIC_ANALYZING`` 동안 공유 ``/storage`` 볼륨에서 읽어 사용자 화면의 세부
진행률을 합성합니다.

단방향 파일 IPC이며 진행률은 보조 정보이므로, 여기서 실패하더라도 분석 자체는 계속
진행되어야 합니다(모든 예외를 삼키고 debug 로그만 남깁니다). 이 파일은 분석이 끝나면
``media_io.cleanup_temp_directory`` 가 temp 디렉터리와 함께 삭제합니다.
"""

import json
import logging
import os
from datetime import datetime, timezone
from typing import Any, Dict

from app.core.paths import resolve_project_root

logger = logging.getLogger("analysis-engine")

PROGRESS_FILE_NAME = "progress.json"


def write_basic_progress(
    job_id: str,
    step_no: int,
    total_steps: int,
    key: str,
    label: str,
) -> None:
    try:
        temp_directory = (
            resolve_project_root() / "storage" / "temp" / job_id
        ).resolve()
        temp_directory.mkdir(parents=True, exist_ok=True)

        payload: Dict[str, Any] = {
            "jobId": job_id,
            "phase": "BASIC_ANALYSIS",
            "stepNo": step_no,
            "totalSteps": total_steps,
            "stepKey": key,
            "label": label,
            "updatedAtIso": datetime.now(timezone.utc).isoformat(),
        }

        target_path = temp_directory / PROGRESS_FILE_NAME
        temp_path = temp_directory / f"{PROGRESS_FILE_NAME}.tmp"
        temp_path.write_text(
            json.dumps(payload, ensure_ascii=False),
            encoding="utf-8",
        )
        os.replace(temp_path, target_path)
    except Exception as exception:  # noqa: BLE001 - 진행률 기록 실패는 분석을 막지 않습니다.
        logger.debug("(%s) progress.json 기록을 건너뜁니다: %s", job_id, exception)
