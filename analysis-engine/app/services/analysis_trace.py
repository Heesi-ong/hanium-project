"""기본 분석 파이프라인이 각 단계에서 무엇을 했는지 순서대로 남기는 트레이스 수집기.

basic_analysis가 단계마다 :meth:`AnalysisTrace.step` 으로 항목을 열고, 필요하면
:meth:`AnalysisTrace.detail` 로 "무엇을 얼마나 처리했는지"(예: 프레임 20장, 포즈 검출
17/20)를 덧붙입니다. 다음 ``step()`` 이나 마지막 ``finish()`` 가 직전 항목의
``durationMs`` 를 채웁니다.

결과 리스트는 분석 응답 JSON의 ``analysisTrace`` 로 나가 사용자에게 "이 영상이 이렇게
분석됐다"를 보여주는 데 쓰입니다. 분석 정확도와는 무관한 부가 정보이므로, 이 모듈에서
예외가 나더라도 파이프라인을 막지 않도록 호출부에서 감쌉니다.
"""

import time
from datetime import datetime, timezone
from typing import Any, Dict, List


def _utcnow_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class AnalysisTrace:
    def __init__(self, total_steps: int) -> None:
        self._total_steps = total_steps
        self._entries: List[Dict[str, Any]] = []
        self._monotonic_by_index: Dict[int, float] = {}

    def step(self, step_no: int, key: str, label: str) -> None:
        now = time.monotonic()
        self._close_last(now)
        self._monotonic_by_index[len(self._entries)] = now
        self._entries.append(
            {
                "stepNo": step_no,
                "totalSteps": self._total_steps,
                "key": key,
                "label": label,
                "startedAtIso": _utcnow_iso(),
                "durationMs": None,
                "detail": None,
            }
        )

    def detail(self, detail: str) -> None:
        if self._entries:
            self._entries[-1]["detail"] = detail

    def finish(self) -> None:
        self._close_last(time.monotonic())

    def to_list(self) -> List[Dict[str, Any]]:
        return [dict(entry) for entry in self._entries]

    def _close_last(self, now: float) -> None:
        if not self._entries:
            return

        last_index = len(self._entries) - 1
        if self._entries[last_index]["durationMs"] is not None:
            return

        started = self._monotonic_by_index.get(last_index, now)
        self._entries[last_index]["durationMs"] = round((now - started) * 1000, 1)
