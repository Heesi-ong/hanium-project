// 단일 분석 결과의 점수, 타임라인, 피드백, 연습 코칭, 보고서 다운로드를 보여준다.
import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { downloadAnalyzeReport } from "../api/analyzeApi";
import StateMessage from "../components/StateMessage";
import Button from "../components/ui/Button";
import Card from "../components/ui/Card";
import ScoreRing from "../components/ui/ScoreRing";
import { ScoreBarChart, TimelineLineChart } from "../features/analysis/ResultCharts";
import { clampScore, formatNumber, getScoreClassName } from "../features/analysis/formatters";
import { buildScoreCards, getTimelineStats } from "../features/analysis/resultDetailModel";
import useResultDetailData from "../features/analysis/useResultDetailData";
import AiCoachingSection from "../features/practice/AiCoachingSection";
import PracticeCoachingSections from "../features/practice/PracticeCoachingSections";

import "./ResultDetailPage.css";

const getBarClassName = (score) => {
  if (typeof score !== "number") return "timeline-bar unavailable";
  if (score >= 80) return "timeline-bar good";
  if (score >= 60) return "timeline-bar normal";
  return "timeline-bar bad";
};

function ResultDetailPage() {
  const navigate = useNavigate();
  const { resultId } = useParams();
  const [reportError, setReportError] = useState("");
  const [reportDownloading, setReportDownloading] = useState(false);

  const {
    aiCoaching,
    aiCoachingError,
    aiCoachingLoading,
    chartData,
    coaching,
    coachingError,
    coachingLoading,
    error,
    fileName,
    generateAiCoaching,
    loadCoaching,
    loadData,
    loadTimeline,
    loading,
    sections,
    timelineError,
    timelineLoading,
  } = useResultDetailData(resultId);

  const handleDownloadReport = async () => {
    try {
      setReportDownloading(true);
      setReportError("");
      const blob = await downloadAnalyzeReport(resultId);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${resultId}.md`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (requestError) {
      setReportError(requestError.message || "Markdown 보고서를 내려받지 못했습니다.");
    } finally {
      setReportDownloading(false);
    }
  };

  const timelineStats = getTimelineStats(chartData);

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
              <Button variant="secondary" onClick={() => navigate("/results")}>
                목록으로 돌아가기
              </Button>

              <Button onClick={loadData}>다시 불러오기</Button>
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
  const scoreCards = buildScoreCards(score);
  const practiceQuestion = (question) => {
    navigate(`/chat/result/${resultId}?question=${encodeURIComponent(question)}`, {
      state: {
        analysisTitle: `${fileName} 예상 질문 연습`,
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
          <Button variant="secondary" onClick={() => navigate("/results")}>
            목록
          </Button>

          <Button variant="secondary" onClick={() => navigate("/upload")}>
            새 분석
          </Button>

          <Button onClick={loadData}>새로고침</Button>
          <Button
            onClick={() =>
              navigate(`/chat/result/${resultId}`, {
                state: {
                  analysisTitle: `${fileName} 코칭`,
                },
              })
            }
          >
            이 결과로 AI 코치 상담
          </Button>
          <Button variant="secondary" onClick={handleDownloadReport} disabled={reportDownloading}>
            {reportDownloading ? "보고서 준비 중" : "Markdown 보고서"}
          </Button>
        </div>
      </header>

      {reportError && (
        <StateMessage type="error" title="Markdown 보고서를 내려받지 못했습니다.">
          {reportError}
        </StateMessage>
      )}

      <Card accent="primary" className="detail-hero-card">
        <div className="detail-file-block">
          <div className="metric-label">분석 파일</div>
          <div className="detail-file-name">{fileName}</div>
        </div>

        <div className="detail-hero-grid">
          <ScoreRing
            className="detail-score-circle"
            label="TOTAL"
            score={totalScore}
            size="lg"
            value={totalScore === null ? "측정 불가" : formatNumber(totalScore)}
          />

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
      </Card>

      <PracticeCoachingSections
        coaching={coaching}
        loading={coachingLoading}
        error={coachingError}
        onRetry={loadCoaching}
        onPracticeQuestion={practiceQuestion}
      />

      <AiCoachingSection
        aiCoaching={aiCoaching}
        loading={aiCoachingLoading}
        error={aiCoachingError}
        onGenerate={() => generateAiCoaching(false)}
        onRegenerate={() => generateAiCoaching(true)}
        onContinueChat={() =>
          navigate(`/chat/result/${resultId}`, {
            state: { analysisTitle: `${fileName} AI 발표 코칭` },
          })
        }
        onPracticeQuestion={practiceQuestion}
      />

      <section className="detail-score-grid">
        {scoreCards.map((item) => (
          <Card className="detail-score-card" key={item.key}>
            <div className="metric-label">{item.label}</div>
            <div className={`metric-value ${item.confidence ? "" : getScoreClassName(item.score)}`}>
              {item.value}
            </div>
          </Card>
        ))}
      </section>
      <p className="detail-muted-text">
        감지율은 촬영 환경에 따른 분석 신뢰도이며 발표 실력 점수에는 포함하지 않습니다.
      </p>

      <ScoreBarChart items={scoreCards} />

      <section className="detail-section-grid">
        <Card as="article" className="detail-section-card">
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
        </Card>

        <Card as="article" className="detail-section-card">
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
        </Card>

        <Card as="article" className="detail-section-card">
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
        </Card>

        <Card as="article" className="detail-section-card">
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
        </Card>
      </section>

      <Card className="detail-timeline-card">
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
            actions={<Button onClick={loadTimeline}>타임라인 다시 불러오기</Button>}
          >
            기본 분석 결과는 정상적으로 확인할 수 있습니다. {timelineError}
          </StateMessage>
        ) : chartData.length === 0 ? (
          <p className="detail-muted-text">타임라인 데이터가 없습니다.</p>
        ) : (
          <>
            <TimelineLineChart data={chartData} />
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
                      {item.shoulder_score === null
                        ? "측정 불가"
                        : formatNumber(item.shoulder_score)}
                    </span>
                    <span>얼굴 감지 {item.face_score === null ? "미감지" : "감지"}</span>
                    <span>
                      얼굴 방향{" "}
                      {item.gaze_score === null ? "측정 불가" : formatNumber(item.gaze_score)}
                    </span>
                    {typeof item.head_direction_score === "number" && (
                      <span>
                        3축 방향 {formatNumber(item.head_direction_score)} · yaw{" "}
                        {formatNumber(item.yaw_degrees, "°")} · pitch{" "}
                        {formatNumber(item.pitch_degrees, "°")} · roll{" "}
                        {formatNumber(item.roll_degrees, "°")}
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </Card>
    </div>
  );
}

export default ResultDetailPage;
