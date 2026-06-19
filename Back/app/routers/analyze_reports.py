"""완료된 분석 결과를 사용자 다운로드용 Markdown 보고서로 변환한다."""

from fastapi import APIRouter, Depends
from fastapi.responses import PlainTextResponse

from ..services.auth_service import get_current_user
from .analyze_common import require_completed_result

router = APIRouter(prefix="/analyze", tags=["Analyze"])


def _timeline_sort_key(item):
    score = item.get("frame_score")
    return (0, score) if isinstance(score, (int, float)) else (1, 101)


def _format_report_metric(value):
    return f"{value}점" if isinstance(value, (int, float)) else "측정 불가"


@router.get("/result/{result_id}/report.md", response_class=PlainTextResponse)
def download_markdown_report(result_id: str, user=Depends(get_current_user)):
    result = require_completed_result(result_id, user["id"])
    data = result.get("data", {})
    summary = data.get("summary_result", {})
    score = data.get("score_result", {})
    feedback = data.get("feedback_result", {})
    metrics = summary.get("metrics", {})
    lines = [
        f"# 발표 분석 보고서: {data.get('original_filename') or result_id}",
        "",
        f"- 분석 ID: {result_id}",
        f"- 생성일: {result.get('created_at', '-')}",
        f"- 종합 점수: {summary.get('total_score', score.get('total_score', '-'))}",
        f"- 처리 시간: {summary.get('processing_time_seconds', '-')}초",
        "",
        "## 요약 피드백",
        "",
        summary.get("summary_feedback") or feedback.get("summary") or "피드백 없음",
        "",
        "## 핵심 지표",
        "",
    ]
    for key, value in metrics.items():
        lines.append(f"- {key}: {value}")
    details = feedback.get("details", [])
    if details:
        lines.extend(["", "## 개선 제안", ""])
        lines.extend(f"- {item}" for item in details)
    timeline = data.get("timeline_result", {}).get("timeline", [])
    weak_timeline = sorted(timeline, key=_timeline_sort_key)[:5]
    if weak_timeline:
        lines.extend(["", "## 집중 연습 구간", ""])
        lines.extend(
            f"- {item.get('time_sec', '-')}초: 종합 {_format_report_metric(item.get('frame_score'))}, "
            f"자세 {_format_report_metric(item.get('pose_score'))}, "
            f"시선 {_format_report_metric(item.get('gaze_score'))}"
            for item in weak_timeline
        )
    response = PlainTextResponse("\n".join(lines), media_type="text/markdown; charset=utf-8")
    response.headers["Content-Disposition"] = f'attachment; filename="{result_id}.md"'
    return response
