// 점수, 날짜, 측정 불가 값, 상태 색상 같은 분석 화면 표시 형식을 공통 처리한다.
export const formatNumber = (value, suffix = "") => {
  if (value === null || value === undefined || value === "") return "-";
  if (typeof value === "number") {
    return `${Number.isInteger(value) ? value : value.toFixed(2)}${suffix}`;
  }
  return `${value}${suffix}`;
};

export const getScoreClassName = (score) => {
  if (typeof score !== "number") return "";
  if (score >= 80) return "score-good";
  if (score >= 60) return "score-normal";
  return "score-bad";
};

export const clampScore = (score) => {
  if (typeof score !== "number") return 0;
  return Math.min(100, Math.max(0, score));
};

export const formatDateTime = (value) => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
};
