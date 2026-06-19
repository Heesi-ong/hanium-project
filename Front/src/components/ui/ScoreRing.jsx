// 점수를 원형 게이지로 시각화하는 공통 UI 컴포넌트다.
import { clampScore, getScoreClassName } from "../../features/analysis/formatters";
import "./ui.css";

function ScoreRing({
  className = "",
  label = "SCORE",
  score,
  size = "md",
  unavailableLabel = "측정 불가",
  value,
}) {
  const available = typeof score === "number";
  const displayValue = value ?? (available ? score : unavailableLabel);
  const classes = [
    "score-circle",
    "ui-score-ring",
    `ui-score-ring-${size}`,
    available ? "" : "unavailable",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={classes} style={available ? { "--score": clampScore(score) } : undefined}>
      <div className="score-circle-inner ui-score-ring-inner">
        <div className={`score-circle-value ui-score-ring-value ${getScoreClassName(score)}`}>
          {displayValue}
        </div>
        <div className="score-circle-label ui-score-ring-label">{label}</div>
      </div>
    </div>
  );
}

export default ScoreRing;
