// 분석 결과 점수 분포와 시간대별 변화를 SVG 기반 차트로 보여준다.
import { clampScore, formatNumber, getScoreClassName } from "./formatters";

const CHART_WIDTH = 640;
const CHART_HEIGHT = 220;
const PADDING = {
  top: 18,
  right: 18,
  bottom: 34,
  left: 42,
};

const getNumericScore = (value) => (typeof value === "number" ? clampScore(value) : null);

export function ScoreBarChart({ items }) {
  return (
    <div className="analysis-chart-card">
      <div className="analysis-chart-header">
        <h2>지표별 점수 분포</h2>
        <p>측정 가능한 지표만 막대로 비교합니다.</p>
      </div>

      <div className="score-bar-chart" aria-label="분석 지표별 점수 막대 차트">
        {items.map((item) => {
          const score = getNumericScore(item.score);
          const unavailable = score === null;

          return (
            <div className="score-bar-row" key={item.key || item.label}>
              <div className="score-bar-label">
                <span>{item.label}</span>
                <strong className={item.confidence ? "" : getScoreClassName(item.score)}>
                  {item.value}
                </strong>
              </div>
              <div
                className={`score-bar-track ${unavailable ? "unavailable" : ""}`}
                role="progressbar"
                aria-label={`${item.label} ${item.value}`}
                aria-valuemin="0"
                aria-valuemax="100"
                aria-valuenow={unavailable ? undefined : score}
              >
                {!unavailable && (
                  <span
                    className={`score-bar-fill ${item.confidence ? "confidence" : getScoreClassName(item.score)}`}
                    style={{ width: `${score}%` }}
                  />
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function TimelineLineChart({ data }) {
  const numericData = data.filter((item) => typeof item.frame_score === "number");

  if (numericData.length === 0) {
    return (
      <div className="analysis-chart-card timeline-line-chart-empty">
        <h2>타임라인 변화 차트</h2>
        <p>차트로 표시할 수 있는 시간대별 점수가 없습니다.</p>
      </div>
    );
  }

  const minTime = Math.min(...numericData.map((item) => Number(item.time_sec) || 0));
  const maxTime = Math.max(...numericData.map((item) => Number(item.time_sec) || 0));
  const plotWidth = CHART_WIDTH - PADDING.left - PADDING.right;
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom;
  const xScale = (time) =>
    PADDING.left + ((Number(time) - minTime) / Math.max(1, maxTime - minTime)) * plotWidth;
  const yScale = (score) => PADDING.top + (1 - clampScore(score) / 100) * plotHeight;
  const points = numericData
    .map((item) => `${xScale(item.time_sec).toFixed(1)},${yScale(item.frame_score).toFixed(1)}`)
    .join(" ");
  const lastPoint = numericData[numericData.length - 1];

  return (
    <div className="analysis-chart-card">
      <div className="analysis-chart-header">
        <h2>타임라인 변화 차트</h2>
        <p>시간이 흐르면서 프레임 점수가 어떻게 흔들리는지 확인합니다.</p>
      </div>

      <div className="timeline-line-chart" role="img" aria-label="시간대별 프레임 점수 선형 차트">
        <svg viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`} aria-hidden="true">
          {[0, 25, 50, 75, 100].map((tick) => {
            const y = yScale(tick);
            return (
              <g key={tick}>
                <line
                  className="timeline-chart-grid-line"
                  x1={PADDING.left}
                  x2={CHART_WIDTH - PADDING.right}
                  y1={y}
                  y2={y}
                />
                <text className="timeline-chart-axis-label" x="8" y={y + 4}>
                  {tick}
                </text>
              </g>
            );
          })}
          <line
            className="timeline-chart-axis"
            x1={PADDING.left}
            x2={CHART_WIDTH - PADDING.right}
            y1={CHART_HEIGHT - PADDING.bottom}
            y2={CHART_HEIGHT - PADDING.bottom}
          />
          <polyline className="timeline-chart-line" points={points} />
          {numericData.map((item) => (
            <circle
              className="timeline-chart-point"
              cx={xScale(item.time_sec)}
              cy={yScale(item.frame_score)}
              key={`${item.time_sec}-${item.frame_score}`}
              r="4"
            />
          ))}
          <text className="timeline-chart-axis-label" x={PADDING.left} y={CHART_HEIGHT - 10}>
            {minTime}s
          </text>
          <text
            className="timeline-chart-axis-label timeline-chart-axis-label-end"
            x={CHART_WIDTH - PADDING.right}
            y={CHART_HEIGHT - 10}
          >
            {maxTime}s
          </text>
        </svg>
      </div>

      <p className="timeline-line-chart-summary">
        마지막 측정 구간 {lastPoint.time_sec}s 점수는 {formatNumber(lastPoint.frame_score)}입니다.
      </p>
    </div>
  );
}
