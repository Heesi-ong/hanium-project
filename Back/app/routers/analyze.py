"""분석 관련 하위 라우터를 하나의 `/analyze` API 라우터로 묶어 노출한다."""

from fastapi import APIRouter

from .analyze_common import (
    _require_completed_result,
    _require_owned_job,
    require_completed_result,
    require_owned_job,
)
from .analyze_deletion import delete_result
from .analyze_deletion import router as deletion_router
from .analyze_reports import download_markdown_report
from .analyze_reports import router as reports_router
from .analyze_results import (
    get_growth,
    get_result,
    get_result_sections,
    get_result_summary,
    get_result_timeline,
    get_result_timeline_chart,
    get_results,
)
from .analyze_results import (
    router as results_router,
)
from .analyze_upload import (
    analyze_home,
    cancel_job,
    get_job_status,
    retry_job,
    upload_video,
)
from .analyze_upload import (
    router as upload_router,
)

router = APIRouter()
router.include_router(upload_router)
router.include_router(results_router)
router.include_router(reports_router)
router.include_router(deletion_router)

__all__ = [
    "_require_completed_result",
    "_require_owned_job",
    "analyze_home",
    "cancel_job",
    "delete_result",
    "download_markdown_report",
    "get_growth",
    "get_job_status",
    "get_result",
    "get_result_sections",
    "get_result_summary",
    "get_result_timeline",
    "get_result_timeline_chart",
    "get_results",
    "require_completed_result",
    "require_owned_job",
    "retry_job",
    "router",
    "upload_video",
]
