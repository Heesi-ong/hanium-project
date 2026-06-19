// 분석 작업 상태값을 사용자가 읽기 쉬운 배지로 변환한다.
import "./ui.css";

const statusMeta = {
  CANCELLED: {
    className: "status-cancelled",
    icon: "○",
    text: "분석 취소",
  },
  COMPLETED: {
    className: "status-completed",
    icon: "●",
    text: "분석 완료",
  },
  FAILED: {
    className: "status-failed",
    icon: "●",
    text: "분석 실패",
  },
  PROCESSING: {
    className: "status-processing",
    icon: "●",
    text: "처리 중",
  },
  QUEUED: {
    className: "status-processing",
    icon: "●",
    text: "대기 중",
  },
};

function StatusBadge({ className = "", status, withIcon = false }) {
  const meta = statusMeta[status] || statusMeta.PROCESSING;
  const classes = ["status-badge", "ui-status-badge", meta.className, className]
    .filter(Boolean)
    .join(" ");

  return (
    <span className={classes}>
      {withIcon && (
        <span className="ui-status-badge-icon" aria-hidden="true">
          {meta.icon}
        </span>
      )}
      {meta.text}
    </span>
  );
}

export default StatusBadge;
