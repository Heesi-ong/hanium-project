// 결과 상세 화면에서 쓰는 점수 카드와 타임라인 통계를 계산한다.
import { formatNumber } from "./formatters";

export const getTimelineStats = (chartData) => {
  const scores = chartData
    .map((item) => item.frame_score)
    .filter((value) => typeof value === "number");

  if (scores.length === 0) {
    return {
      average: null,
      best: null,
      weak: null,
    };
  }

  const total = scores.reduce((sum, score) => sum + score, 0);

  return {
    average: Math.round(total / scores.length),
    best: Math.max(...scores),
    weak: Math.min(...scores),
  };
};

export const isScoreAvailable = (score, key, value) => {
  if (score.confidence_availability && key in score.confidence_availability) {
    return score.confidence_availability[key];
  }
  if (score.score_availability && key in score.score_availability) {
    return score.score_availability[key];
  }
  if (
    ["pose_detection_rate", "shoulder_balance_score"].includes(key) &&
    score.pose_detected_count === 0
  ) {
    return false;
  }
  if (
    ["face_detection_rate", "gaze_score", "head_direction_score"].includes(key) &&
    score.face_detected_count === 0
  ) {
    return false;
  }
  return value !== null && value !== undefined;
};

export const buildScoreCards = (score) => [
  {
    key: "pose_detection_rate",
    label: "자세 분석 신뢰도 (감지율)",
    value: isScoreAvailable(score, "pose_detection_rate", score.pose_detection_rate)
      ? formatNumber(score.pose_detection_rate, "%")
      : "측정 불가",
    score: score.pose_detection_rate,
    confidence: true,
  },
  {
    key: "face_detection_rate",
    label: "얼굴 방향 분석 신뢰도 (감지율)",
    value: isScoreAvailable(score, "face_detection_rate", score.face_detection_rate)
      ? formatNumber(score.face_detection_rate, "%")
      : "측정 불가",
    score: score.face_detection_rate,
    confidence: true,
  },
  {
    key: "shoulder_balance_score",
    label: "어깨 균형",
    value: isScoreAvailable(score, "shoulder_balance_score", score.shoulder_balance_score)
      ? formatNumber(score.shoulder_balance_score)
      : "측정 불가",
    score: score.shoulder_balance_score,
  },
  {
    key: "gaze_score",
    label: "얼굴 방향 안정성",
    value: isScoreAvailable(score, "gaze_score", score.gaze_score)
      ? formatNumber(score.gaze_score)
      : "측정 불가",
    score: score.gaze_score,
  },
  {
    key: "head_direction_score",
    label: "3축 얼굴 방향 (실험)",
    value: isScoreAvailable(score, "head_direction_score", score.head_direction_score)
      ? formatNumber(score.head_direction_score)
      : "측정 불가",
    score: score.head_direction_score,
  },
];
