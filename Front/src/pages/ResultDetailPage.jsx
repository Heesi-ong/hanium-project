import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import {
  getAnalyzeReportUrl,
  getAnalyzeSections,
  getPracticeCoaching,
  getTimelineChart,
} from "../api/analyzeApi";
import StateMessage from "../components/StateMessage";
import PracticeCoachingSections from "../features/practice/PracticeCoachingSections";

import "./ResultDetailPage.css";

const formatNumber = (value, suffix = "") => {
  if (value === null || value === undefined || value === "") {
    return "-";
  }

  if (typeof value === "number") {
    return `${Number.isInteger(value) ? value : value.toFixed(2)}${suffix}`;
  }

  return `${value}${suffix}`;
};

const getScoreClassName = (score) => {
  if (score === null || score === undefined) return "";
  if (score >= 80) return "score-good";
  if (score >= 60) return "score-normal";
  return "score-bad";
};

const getBarClassName = (score) => {
  if (typeof score !== "number") return "timeline-bar unavailable";
  if (score >= 80) return "timeline-bar good";
  if (score >= 60) return "timeline-bar normal";
  return "timeline-bar bad";
};

const clampScore = (score) => {
  if (typeof score !== "number") return 0;
  return Math.min(100, Math.max(0, score));
};

function ResultDetailPage() {
  const navigate = useNavigate();
  const { resultId } = useParams();

  const [sections, setSections] = useState(null);
  const [chartData, setChartData] = useState([]);
  const [fileName, setFileName] = useState("");
  const [coaching, setCoaching] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [timelineLoading, setTimelineLoading] = useState(true);
  const [timelineError, setTimelineError] = useState("");
  const [coachingLoading, setCoachingLoading] = useState(true);
  const [coachingError, setCoachingError] = useState("");
  const requestControllers = useRef({});

  const startRequest = (key) => {
    requestControllers.current[key]?.abort();
    const controller = new AbortController();
    requestControllers.current[key] = controller;
    return controller;
  };

  const loadCoaching = async () => {
    const controller = startRequest("coaching");
    const requestedResultId = resultId;
    try {
      setCoachingLoading(true);
      setCoachingError("");
      const response = await getPracticeCoaching(resultId, controller.signal);
      if (requestedResultId === resultId && requestControllers.current.coaching === controller) {
        setCoaching(response.coaching || null);
      }
    } catch (err) {
      if (err.name !== "AbortError" && requestControllers.current.coaching === controller) {
        setCoaching(null);
        setCoachingError(err.message || "연습 코칭을 불러오지 못했습니다.");
      }
    } finally {
      if (requestControllers.current.coaching === controller) {
        setCoachingLoading(false);
      }
    }
  };

  const loadSections = async () => {
    const controller = startRequest("sections");
    const requestedResultId = resultId;
    try {
      setLoading(true);
      setError("");
      const response = await getAnalyzeSections(resultId, controller.signal);
      if (requestedResultId === resultId && requestControllers.current.sections === controller) {
        setSections(response.sections);
        setFileName(response.original_filename || "파일명 없음");
      }
    } catch (err) {
      if (err.name !== "AbortError" && requestControllers.current.sections === controller) {
        setError(err.message || "분석 상세 결과를 불러오지 못했습니다.");
        setSections(null);
      }
    } finally {
      if (requestControllers.current.sections === controller) {
        setLoading(false);
      }
    }
  };

  const loadTimeline = async () => {
    const controller = startRequest("timeline");
    const requestedResultId = resultId;
    try {
      setTimelineLoading(true);
      setTimelineError("");
      const response = await getTimelineChart(resultId, controller.signal);
      if (requestedResultId === resultId && requestControllers.current.timeline === controller) {
        setChartData(response.chart_data || []);
      }
    } catch (err) {
      if (err.name !== "AbortError" && requestControllers.current.timeline === controller) {
        setChartData([]);
        setTimelineError(err.message || "타임라인을 불러오지 못했습니다.");
      }
    } finally {
      if (requestControllers.current.timeline === controller) {
        setTimelineLoading(false);
      }
    }
  };

  const loadData = () => {
    void loadSections();
    void loadTimeline();
    void loadCoaching();
  };

  useEffect(() => {
    setSections(null);
    setChartData([]);
    setCoaching(null);
    loadData();
    return () =>
      Object.values(requestControllers.current).forEach((controller) => controller.abort());
  }, [resultId]);

  const timelineStats = useMemo(() => {
    if (chartData.length === 0) {
      return {
        average: null,
        best: null,
        weak: null,
      };
    }

    const scores = chartData
      .map((item) => item.frame_score)
      .filter((value) => typeof value === "number");
    if (scores.length === 0) {
      return { average: null, best: null, weak: null };
    }
    const total = scores.reduce((sum, score) => sum + score, 0);

    return {
      average: Math.round(total / scores.length),
      best: Math.max(...scores),
      weak: Math.min(...scores),
    };
  }, [chartData]);

  if (loading) {
    return (
      <div className="page result-detail-page">
        <StateMessage title="분석 상세 결과를 불러오는 중입니다." />
      </div>
    );
  }

  if (error || !sections) {
    return (
      <div className="page result-detail-page">
        <StateMessage
          type="error"
          title="분석 상세 결과를 불러오지 못했습니다."
          actions={
            <>
              <button className="button secondary" onClick={() => navigate("/results")}>
                목록으로 돌아가기
              </button>

              <button className="button" onClick={loadData}>
                다시 불러오기
              </button>
            </>
          }
        >
          {error || "결과를 찾을 수 없습니다."}
        </StateMessage>
      </div>
    );
  }

  const summary = sections.summary || {};
  const score = sections.score || {};
  const feedback = sections.feedback || {};
  const speech = sections.speech || {};
  const filler = sections.filler || {};
  const gesture = sections.gesture || {};
  const volume = sections.volume || {};
  const timeline = sections.timeline || {};
  const fillerWords = filler.filler_words || {};
  const totalScore = summary.total_score ?? score.total_score ?? null;
  const scoreAvailable = (key, value) => {
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
    if (["face_detection_rate", "gaze_score"].includes(key) && score.face_detected_count === 0) {
      return false;
    }
    return value !== null && value !== undefined;
  };
  const weakTimeline = [...chartData]
    .filter((item) => typeof item.frame_score === "number")
    .sort((left, right) => (left.frame_score ?? 100) - (right.frame_score ?? 100))
    .slice(0, 3)
    .map((item) => `${item.time_sec ?? "-"}초(${item.frame_score ?? "-"}점)`)
    .join(", ");
  const coachContext = [
    `발표 파일: ${fileName}`,
    `발표 목적: ${coaching?.purpose?.label || "프로젝트 발표"}`,
    `발표 대상: ${coaching?.context?.audience || "일반 청중"}`,
    `핵심 메시지: ${coaching?.context?.core_message || "미입력"}`,
    `종합 점수: ${totalScore}`,
    `요약 피드백: ${summary.summary_feedback || feedback.summary || "없음"}`,
    `자세 인식률: ${score.pose_detection_rate ?? "-"}`,
    `얼굴 방향 안정성: ${score.gaze_score ?? "-"}`,
    `말하기 속도 점수: ${speech.speech_speed_score ?? "-"}`,
    `손동작 점수: ${gesture.gesture_score ?? "-"}`,
    `집중 연습 시간대: ${weakTimeline || "없음"}`,
  ].join("\n");

  const scoreCards = [
    {
      label: "자세 분석 신뢰도 (감지율)",
      value: scoreAvailable("pose_detection_rate", score.pose_detection_rate)
        ? formatNumber(score.pose_detection_rate, "%")
        : "측정 불가",
      score: score.pose_detection_rate,
      confidence: true,
    },
    {
      label: "얼굴 방향 분석 신뢰도 (감지율)",
      value: scoreAvailable("face_detection_rate", score.face_detection_rate)
        ? formatNumber(score.face_detection_rate, "%")
        : "측정 불가",
      score: score.face_detection_rate,
      confidence: true,
    },
    {
      label: "어깨 균형",
      value: scoreAvailable("shoulder_balance_score", score.shoulder_balance_score)
        ? formatNumber(score.shoulder_balance_score)
        : "측정 불가",
      score: score.shoulder_balance_score,
    },
    {
      label: "얼굴 방향 안정성",
      value: scoreAvailable("gaze_score", score.gaze_score)
        ? formatNumber(score.gaze_score)
        : "측정 불가",
      score: score.gaze_score,
    },
  ];
  const practiceQuestion = (question) => {
    navigate("/chat", {
      state: {
        analysisContext: `${coachContext}\n예상 질문: ${question}\n사용자의 답변을 평가하고 구체적인 후속 질문을 한 개씩 제시해라.`,
        analysisTitle: `${fileName} 예상 질문 연습`,
        practiceQuestion: question,
      },
    });
  };

  return (
    <div className="page result-detail-page">
      <header className="detail-header">
        <div>
          <p className="detail-kicker">Presentation Analysis</p>
          <h1 className="page-title detail-title">분석 상세 결과</h1>
          <p className="detail-subtitle">
            점수, 피드백, 음성 지표, 타임라인 변화를 한 화면에서 확인합니다.
          </p>
        </div>

        <div className="detail-action-row">
          <button className="button secondary" onClick={() => navigate("/results")}>
            목록
          </button>

          <button className="button secondary" onClick={() => navigate("/upload")}>
            새 분석
          </button>

          <button className="button" onClick={loadData}>
            새로고침
          </button>
          <button
            className="button"
            onClick={() =>
              navigate("/chat", {
                state: {
                  analysisContext: coachContext,
                  analysisTitle: `${fileName} 코칭`,
                },
              })
            }
          >
            이 결과로 AI 코치 상담
          </button>
          <a className="button secondary" href={getAnalyzeReportUrl(resultId)} download>
            Markdown 보고서
          </a>
        </div>
      </header>

      <section className="card detail-hero-card">
        <div className="detail-file-block">
          <div className="metric-label">분석 파일</div>
          <div className="detail-file-name">{fileName}</div>
        </div>

        <div className="detail-hero-grid">
          <div
            className={`score-circle detail-score-circle ${totalScore === null ? "unavailable" : ""}`}
            style={totalScore === null ? undefined : { "--score": clampScore(totalScore) }}
          >
            <div className="score-circle-inner">
              <div className={`score-circle-value ${getScoreClassName(totalScore)}`}>
                {totalScore === null ? "측정 불가" : formatNumber(totalScore)}
              </div>
              <div className="score-circle-label">TOTAL</div>
            </div>
          </div>

          <div className="detail-summary-panel">
            <h2>요약 피드백</h2>
            <p className="detail-feedback-main">
              {summary.summary_feedback || feedback.summary || "요약 피드백이 없습니다."}
            </p>

            {Array.isArray(feedback.details) && feedback.details.length > 0 && (
              <ul className="detail-feedback-list">
                {feedback.details.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            )}
          </div>

          <div className="detail-meta-grid">
            <div className="metric-item">
              <div className="metric-label">영상 길이</div>
              <div className="metric-value">{formatNumber(timeline.duration_seconds, "초")}</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">타임라인 수</div>
              <div className="metric-value">{formatNumber(timeline.timeline_count)}</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">처리 시간</div>
              <div className="metric-value">
                {formatNumber(summary.processing_time_seconds, "초")}
              </div>
            </div>
          </div>
        </div>
      </section>

      <PracticeCoachingSections
        coaching={coaching}
        loading={coachingLoading}
        error={coachingError}
        onRetry={loadCoaching}
        onPracticeQuestion={practiceQuestion}
      />

      <section className="detail-score-grid">
        {scoreCards.map((item) => (
          <div className="card detail-score-card" key={item.label}>
            <div className="metric-label">{item.label}</div>
            <div className={`metric-value ${item.confidence ? "" : getScoreClassName(item.score)}`}>
              {item.value}
            </div>
          </div>
        ))}
      </section>
      <p className="detail-muted-text">
        감지율은 촬영 환경에 따른 분석 신뢰도이며 발표 실력 점수에는 포함하지 않습니다.
      </p>

      <section className="detail-section-grid">
        <article className="card detail-section-card">
          <div className="detail-section-header">
            <h2>발표 속도 / 침묵</h2>
            <span className={`detail-score-pill ${getScoreClassName(speech.speech_speed_score)}`}>
              속도 {formatNumber(speech.speech_speed_score)}
            </span>
          </div>

          <div className="metric-grid">
            <div className="metric-item">
              <div className="metric-label">말하기 속도</div>
              <div className="metric-value">{formatNumber(speech.speech_speed_wpm, " WPM")}</div>
            </div>
            <div className="metric-item">
              <div className="metric-label">한국어 발화 속도</div>
              <div className="metric-value">
                {formatNumber(speech.speech_speed_spm, " 음절/분")}
              </div>
            </div>

            <div className="metric-item">
              <div className="metric-label">침묵 횟수</div>
              <div className="metric-value">{formatNumber(speech.silence_count, "회")}</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">침묵 시간</div>
              <div className="metric-value">{formatNumber(speech.total_silence_time, "초")}</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">침묵 점수</div>
              <div className={`metric-value ${getScoreClassName(speech.silence_score)}`}>
                {formatNumber(speech.silence_score)}
              </div>
            </div>
          </div>
        </article>

        <article className="card detail-section-card">
          <div className="detail-section-header">
            <h2>필러 단어</h2>
            <span className={`detail-score-pill ${getScoreClassName(filler.filler_score)}`}>
              점수 {formatNumber(filler.filler_score)}
            </span>
          </div>

          <div className="metric-grid">
            <div className="metric-item">
              <div className="metric-label">필러 횟수</div>
              <div className="metric-value">{formatNumber(filler.filler_count, "회")}</div>
            </div>
            <div className="metric-item">
              <div className="metric-label">분당 필러</div>
              <div className="metric-value">{formatNumber(filler.filler_per_minute, "회/분")}</div>
            </div>
          </div>

          <div className="detail-tag-area">
            {Object.keys(fillerWords).length === 0 ? (
              <p className="detail-muted-text">감지된 필러 단어가 없습니다.</p>
            ) : (
              Object.entries(fillerWords).map(([word, count]) => (
                <span className="tag" key={word}>
                  {word}: {count}회
                </span>
              ))
            )}
          </div>
        </article>

        <article className="card detail-section-card">
          <div className="detail-section-header">
            <h2>손동작 분석</h2>
            <span className={`detail-score-pill ${getScoreClassName(gesture.gesture_score)}`}>
              점수 {formatNumber(gesture.gesture_score)}
            </span>
          </div>

          <div className="metric-grid">
            <div className="metric-item">
              <div className="metric-label">손동작 변화</div>
              <div className="metric-value">
                {formatNumber(gesture.gesture_movement_count, "회")}
              </div>
            </div>

            <div className="metric-item">
              <div className="metric-label">손동작 수준</div>
              <div className="metric-value">{gesture.gesture_level || "-"}</div>
            </div>
            <div className="metric-item">
              <div className="metric-label">분당 손동작 변화</div>
              <div className="metric-value">
                {formatNumber(gesture.gesture_per_minute, "회/분")}
              </div>
            </div>
          </div>
        </article>

        <article className="card detail-section-card">
          <div className="detail-section-header">
            <h2>음량 분석</h2>
            <span className={`detail-score-pill ${getScoreClassName(volume.volume_score)}`}>
              점수 {formatNumber(volume.volume_score)}
            </span>
          </div>

          <div className="metric-grid">
            <div className="metric-item">
              <div className="metric-label">평균 음량</div>
              <div className="metric-value">{formatNumber(volume.mean_volume_db, " dB")}</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">최대 음량</div>
              <div className="metric-value">{formatNumber(volume.max_volume_db, " dB")}</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">음량 수준</div>
              <div className="metric-value">{volume.volume_level || "-"}</div>
            </div>
          </div>
        </article>
      </section>

      <section className="card detail-timeline-card">
        <div className="detail-section-header">
          <div>
            <h2>타임라인 점수</h2>
            <p className="detail-muted-text">
              시간대별 자세, 얼굴 감지, 얼굴 방향 안정성 흐름을 확인합니다.
            </p>
          </div>

          <div className="timeline-stat-grid">
            <div>
              <span className="metric-label">평균</span>
              <strong className={getScoreClassName(timelineStats.average)}>
                {timelineStats.average ?? "측정 불가"}
              </strong>
            </div>
            <div>
              <span className="metric-label">최고</span>
              <strong className={getScoreClassName(timelineStats.best)}>
                {timelineStats.best ?? "측정 불가"}
              </strong>
            </div>
            <div>
              <span className="metric-label">최저</span>
              <strong className={getScoreClassName(timelineStats.weak)}>
                {timelineStats.weak ?? "측정 불가"}
              </strong>
            </div>
          </div>
        </div>

        {timelineLoading ? (
          <StateMessage title="타임라인을 불러오는 중입니다." compact />
        ) : timelineError ? (
          <StateMessage
            type="error"
            title="타임라인만 불러오지 못했습니다."
            actions={
              <button className="button" onClick={loadTimeline}>
                타임라인 다시 불러오기
              </button>
            }
          >
            기본 분석 결과는 정상적으로 확인할 수 있습니다. {timelineError}
          </StateMessage>
        ) : chartData.length === 0 ? (
          <p className="detail-muted-text">타임라인 데이터가 없습니다.</p>
        ) : (
          <div className="timeline-list detail-timeline-list">
            {chartData.map((item) => (
              <div className="timeline-row detail-timeline-row" key={item.time_sec}>
                <div className="timeline-time">{item.time_sec}s</div>

                <div className="timeline-track detail-timeline-track">
                  {typeof item.frame_score === "number" && (
                    <div
                      className={getBarClassName(item.frame_score)}
                      style={{ width: `${clampScore(item.frame_score)}%` }}
                    />
                  )}
                </div>

                <div className="timeline-score">
                  {item.frame_score === null ? "측정 불가" : formatNumber(item.frame_score)}
                </div>

                <div className="timeline-detail-metrics">
                  <span>자세 감지 {item.pose_score === null ? "미감지" : "감지"}</span>
                  <span>
                    어깨{" "}
                    {item.shoulder_score === null ? "측정 불가" : formatNumber(item.shoulder_score)}
                  </span>
                  <span>얼굴 감지 {item.face_score === null ? "미감지" : "감지"}</span>
                  <span>
                    얼굴 방향{" "}
                    {item.gaze_score === null ? "측정 불가" : formatNumber(item.gaze_score)}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

export default ResultDetailPage;
