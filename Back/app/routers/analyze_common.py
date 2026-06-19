from fastapi import HTTPException

from ..services.analysis_jobs import get_user_job, mark_job_result_missing, sync_job_summary
from ..services.result_saver import load_analysis_result


def require_owned_job(result_id, user_id):
    job = get_user_job(result_id, user_id)
    if not job:
        raise HTTPException(status_code=404, detail="분석 결과를 찾을 수 없습니다.")
    return job


def require_completed_result(result_id, user_id):
    job = require_owned_job(result_id, user_id)
    if job["status"] != "COMPLETED":
        raise HTTPException(status_code=409, detail="분석이 아직 완료되지 않았습니다.")
    result = load_analysis_result(result_id)
    if result is None:
        mark_job_result_missing(result_id)
        raise HTTPException(status_code=409, detail="분석 결과 파일이 손상되었거나 존재하지 않습니다.")
    summary = result.get("data", {}).get("summary_result", {})
    file_score = summary.get("total_score")
    db_score = job.get("total_score")
    score_mismatch = (db_score is None) != (file_score is None)
    if db_score is not None and file_score is not None:
        score_mismatch = abs(float(db_score) - float(file_score)) > 0.001
    if score_mismatch:
        sync_job_summary(result_id, summary)
    return result


_require_owned_job = require_owned_job
_require_completed_result = require_completed_result
