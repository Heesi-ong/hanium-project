// 분석 이력 목록에서 단일 분석 작업의 상태, 점수, 액션 버튼을 카드로 표시한다.
import { formatDateTime, getScoreClassName } from "./formatters";
import Button from "../../components/ui/Button";
import Card from "../../components/ui/Card";
import ScoreRing from "../../components/ui/ScoreRing";
import StatusBadge from "../../components/ui/StatusBadge";

const statusAccent = (status) => {
  if (status === "COMPLETED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "CANCELLED") return "muted";
  return "primary";
};

function ResultCard({ actionId, deletingId, item, onDelete, onDetail, onJobAction }) {
  const score = typeof item.total_score === "number" ? item.total_score : null;
  const processing = item.status === "QUEUED" || item.status === "PROCESSING";

  return (
    <Card as="article" accent={statusAccent(item.status)} className="result-list-card">
      <div className="result-card-header">
        <div className="result-card-title-area">
          <div className="result-title-row">
            <h2 className="result-filename" title={item.original_filename}>
              {item.original_filename || "파일명 없음"}
            </h2>
            <StatusBadge status={item.status} withIcon />
          </div>
        </div>
        {item.status === "COMPLETED" && (
          <ScoreRing score={score} size="sm" unavailableLabel="N/A" />
        )}
      </div>
      <div className="metric-grid">
        <div className="metric-item">
          <div className="metric-label">총점</div>
          <div className={`metric-value ${getScoreClassName(score)}`}>
            {score === null ? "측정 불가" : score}
          </div>
        </div>
        <div className="metric-item">
          <div className="metric-label">처리 시간</div>
          <div className="metric-value">{item.processing_time_seconds ?? "-"}초</div>
        </div>
      </div>
      <p className="result-feedback">{item.summary_feedback ?? "-"}</p>
      {processing && (
        <div className="result-progress-area">
          <div className="result-progress-heading">
            <span>{item.stage || "queued"}</span>
            <strong>{item.progress || 0}%</strong>
          </div>
          <div className="result-progress-track">
            <div style={{ width: `${item.progress || 0}%` }} />
          </div>
        </div>
      )}
      {item.error && <p className="error-text">오류: {item.error}</p>}
      <div className="result-date-row">
        <span className="result-date-label">생성일</span>
        <span className="result-date-value">{formatDateTime(item.created_at)}</span>
      </div>
      <div className="result-action-row">
        {item.status === "COMPLETED" && (
          <Button
            onClick={(event) => {
              event.stopPropagation();
              onDetail(item.result_id);
            }}
          >
            상세 보기
          </Button>
        )}
        {processing && (
          <Button
            variant="secondary"
            disabled={actionId === item.result_id}
            onClick={(event) => {
              event.stopPropagation();
              onJobAction(item.result_id, "cancel");
            }}
          >
            {actionId === item.result_id ? "처리 중..." : "취소"}
          </Button>
        )}
        {(item.status === "FAILED" || item.status === "CANCELLED") && item.retry_available && (
          <Button
            variant="secondary"
            disabled={actionId === item.result_id || item.attempt_count >= item.max_attempts}
            onClick={(event) => {
              event.stopPropagation();
              onJobAction(item.result_id, "retry");
            }}
          >
            {actionId === item.result_id ? "처리 중..." : "재시도"}
          </Button>
        )}
        <Button
          variant="danger"
          disabled={deletingId === item.result_id || processing}
          onClick={(event) => {
            event.stopPropagation();
            onDelete(item.result_id);
          }}
        >
          {deletingId === item.result_id ? "삭제 중..." : "삭제"}
        </Button>
      </div>
    </Card>
  );
}

export default ResultCard;
