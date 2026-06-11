import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import {
  getAnalyzeReportUrl,
  getAnalyzeSections,
  getPracticeCoaching,
  getTimelineChart,
} from "../api/analyzeApi";
import StateMessage from "../components/StateMessage";

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
  const requestController = useRef(null);

  const loadData = async () => {
    requestController.current?.abort();
    const controller = new AbortController();
    requestController.current = controller;
    try {
      setLoading(true);
      setError("");

      const [sectionResponse, chartResponse, coachingResponse] = await Promise.all([
        getAnalyzeSections(resultId, controller.signal),
        getTimelineChart(resultId, controller.signal),
        getPracticeCoaching(resultId, controller.signal),
      ]);

      setSections(sectionResponse.sections);
      setChartData(chartResponse.chart_data || []);
      setFileName(sectionResponse.original_filename || "파일명 없음");
      setCoaching(coachingResponse.coaching || null);
    } catch (err) {
      if (err.name !== "AbortError") {
        console.error(err);
        setError(err.message || "분석 상세 결과를 불러오지 못했습니다.");
        setSections(null);
        setChartData([]);
        setCoaching(null);
      }
    } finally {
      if (requestController.current === controller) {
        requestController.current = null;
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    loadData();
    return () => requestController.current?.abort();
  }, [resultId]);

  const timelineStats = useMemo(() => {
    if (chartData.length === 0) {
      return {
        average: 0,
        best: 0,
        weak: 0,
      };
    }

    const scores = chartData.map((item) => item.frame_score ?? 0);
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
  const totalScore = summary.total_score ?? score.total_score ?? 0;
  const weakTimeline = [...chartData]
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
    `시선 점수: ${score.gaze_score ?? "-"}`,
    `말하기 속도 점수: ${speech.speech_speed_score ?? "-"}`,
    `손동작 점수: ${gesture.gesture_score ?? "-"}`,
    `집중 연습 시간대: ${weakTimeline || "없음"}`,
  ].join("\n");

  const scoreCards = [
    {
      label: "자세 인식률",
      value: formatNumber(score.pose_detection_rate, "%"),
      score: score.pose_detection_rate,
    },
    {
      label: "얼굴 인식률",
      value: formatNumber(score.face_detection_rate, "%"),
      score: score.face_detection_rate,
    },
    {
      label: "어깨 균형",
      value: formatNumber(score.shoulder_balance_score),
      score: score.shoulder_balance_score,
    },
    {
      label: "시선 점수",
      value: formatNumber(score.gaze_score),
      score: score.gaze_score,
    },
  ];

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
            className="score-circle detail-score-circle"
            style={{ "--score": clampScore(totalScore) }}
          >
            <div className="score-circle-inner">
              <div className={`score-circle-value ${getScoreClassName(totalScore)}`}>
                {formatNumber(totalScore)}
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

      {coaching && (
        <>
          <section className="card practice-overview-card">
            <div className="detail-section-header">
              <div>
                <p className="detail-kicker">Practice Brief</p>
                <h2>{coaching.purpose.label}</h2>
                <p className="detail-muted-text">{coaching.purpose.focus}</p>
              </div>
              <span className="detail-score-pill">
                목표 {coaching.duration_fit.target_minutes}분 · 실제{" "}
                {coaching.duration_fit.actual_minutes ?? "-"}분
              </span>
            </div>
            <div className="practice-context-grid">
              <div>
                <span>발표 대상</span>
                <strong>{coaching.context.audience || "일반 청중"}</strong>
              </div>
              <div>
                <span>핵심 메시지</span>
                <strong>{coaching.context.core_message || "아직 입력하지 않음"}</strong>
              </div>
              <div>
                <span>반복 연습</span>
                <strong>{coaching.context.series_name || "새 연습"}</strong>
              </div>
              <div>
                <span>이전 대비</span>
                <strong>
                  {coaching.comparison
                    ? `${coaching.comparison.score_change >= 0 ? "+" : ""}${coaching.comparison.score_change}점`
                    : "첫 비교 기록"}
                </strong>
              </div>
            </div>
          </section>

          <section className="card">
            <div className="detail-section-header">
              <div>
                <h2>다음 연습에서 바꿀 3가지</h2>
                <p className="detail-muted-text">
                  낮은 점수 나열이 아니라 다음 촬영에서 실행할 행동으로 정리했습니다.
                </p>
              </div>
            </div>
            <div className="improvement-plan-grid">
              {coaching.improvement_plan.map((item, index) => (
                <article className="improvement-plan-card" key={item.title}>
                  <span className="practice-step">0{index + 1}</span>
                  <h3>{item.title}</h3>
                  <p>{item.action}</p>
                  <strong>연습 과제</strong>
                  <p>{item.exercise}</p>
                </article>
              ))}
            </div>
          </section>

          <section className="card">
            <div className="detail-section-header">
              <div>
                <h2>발표 내용과 구성</h2>
                <p className="detail-muted-text">{coaching.content_analysis.note}</p>
              </div>
            </div>
            {coaching.content_analysis.available ? (
              <>
                <div className="content-structure-grid">
                  {coaching.content_analysis.structure.map((item) => (
                    <div className={item.found ? "found" : "missing"} key={item.part}>
                      <strong>{item.part}</strong>
                      <span>{item.found ? "구조 단서 확인" : "명확한 전환 표현 필요"}</span>
                    </div>
                  ))}
                </div>
                {coaching.content_analysis.evidence.map((item) => (
                  <div className="content-evidence" key={item.issue}>
                    <strong>{item.issue}</strong>
                    <p>“{item.sentence}”</p>
                    <p>{item.rewrite_example}</p>
                  </div>
                ))}
              </>
            ) : (
              <p className="detail-muted-text">
                음성이 포함된 영상으로 다시 연습하면 내용 분석을 확인할 수 있습니다.
              </p>
            )}
          </section>

          <section className="card">
            <div className="detail-section-header">
              <div>
                <h2>예상 질문 연습</h2>
                <p className="detail-muted-text">
                  질문을 선택하면 이 분석 결과와 발표 목적을 Ollama 코치에게 연결합니다.
                </p>
              </div>
            </div>
            <div className="expected-question-list">
              {coaching.expected_questions.map((question) => (
                <button
                  className="expected-question"
                  key={question}
                  onClick={() =>
                    navigate("/chat", {
                      state: {
                        analysisContext: `${coachContext}\n예상 질문: ${question}\n사용자의 답변을 평가하고 구체적인 후속 질문을 한 개씩 제시해라.`,
                        analysisTitle: `${fileName} 예상 질문 연습`,
                        initialMessage: question,
                      },
                    })
                  }
                >
                  <span>{question}</span>
                  <strong>AI 코치와 답변 연습 →</strong>
                </button>
              ))}
            </div>
          </section>

          <section className="card confidence-card">
            <h2>분석 신뢰도 안내</h2>
            <p>
              영상 분석: <strong>{coaching.confidence.visual}</strong> · 음성 분석:{" "}
              <strong>{coaching.confidence.audio}</strong>
            </p>
            <p className="detail-muted-text">{coaching.confidence.note}</p>
          </section>
        </>
      )}

      <section className="detail-score-grid">
        {scoreCards.map((item) => (
          <div className="card detail-score-card" key={item.label}>
            <div className="metric-label">{item.label}</div>
            <div className={`metric-value ${getScoreClassName(item.score)}`}>{item.value}</div>
          </div>
        ))}
      </section>

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
            <p className="detail-muted-text">시간대별 자세, 얼굴, 시선 점수 흐름을 확인합니다.</p>
          </div>

          <div className="timeline-stat-grid">
            <div>
              <span className="metric-label">평균</span>
              <strong className={getScoreClassName(timelineStats.average)}>
                {timelineStats.average}
              </strong>
            </div>
            <div>
              <span className="metric-label">최고</span>
              <strong className={getScoreClassName(timelineStats.best)}>
                {timelineStats.best}
              </strong>
            </div>
            <div>
              <span className="metric-label">최저</span>
              <strong className={getScoreClassName(timelineStats.weak)}>
                {timelineStats.weak}
              </strong>
            </div>
          </div>
        </div>

        {chartData.length === 0 ? (
          <p className="detail-muted-text">타임라인 데이터가 없습니다.</p>
        ) : (
          <div className="timeline-list detail-timeline-list">
            {chartData.map((item) => (
              <div className="timeline-row detail-timeline-row" key={item.time_sec}>
                <div className="timeline-time">{item.time_sec}s</div>

                <div className="timeline-track detail-timeline-track">
                  <div
                    className={getBarClassName(item.frame_score)}
                    style={{ width: `${clampScore(item.frame_score)}%` }}
                  />
                </div>

                <div className="timeline-score">{formatNumber(item.frame_score)}</div>

                <div className="timeline-detail-metrics">
                  <span>자세 {formatNumber(item.pose_score)}</span>
                  <span>어깨 {formatNumber(item.shoulder_score)}</span>
                  <span>얼굴 {formatNumber(item.face_score)}</span>
                  <span>시선 {formatNumber(item.gaze_score)}</span>
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
