import { getPracticeGrowth } from "../api/analyzeApi";
import StateMessage from "../components/StateMessage";
import useAsyncData from "../hooks/useAsyncData";
import "./GrowthPage.css";

const metricLabels = {
  pose_detection_rate: "자세",
  gaze_score: "시선",
  speech_speed_score: "말하기",
  gesture_score: "손동작",
};

const loadGrowth = async (signal) => {
  const result = await getPracticeGrowth(signal);
  return result.growth || [];
};

export default function GrowthPage() {
  const { data, error, loading } = useAsyncData(loadGrowth);
  const growth = data || [];

  return (
    <main className="page growth-page">
      <header className="dashboard-header">
        <div>
          <h1 className="page-title">성장 추이</h1>
          <p className="dashboard-subtitle">완료된 발표 분석을 시간 순서대로 비교합니다.</p>
        </div>
      </header>
      {loading && <StateMessage title="성장 데이터를 불러오는 중입니다." />}
      {error && (
        <StateMessage type="error" title="성장 데이터를 불러오지 못했습니다.">
          {error}
        </StateMessage>
      )}
      {!growth.length && !error && !loading && (
        <StateMessage type="empty" title="비교할 완료 분석 결과가 없습니다.">
          발표 목적을 정하고 영상을 분석하면 다음 연습에서 바꿀 점과 성장 변화를 비교할 수 있습니다.
        </StateMessage>
      )}
      <section className="growth-list">
        {growth.map((item) => (
          <article className="card growth-card" key={item.result_id}>
            <div className="growth-heading">
              <div>
                <h2>{item.original_filename}</h2>
                <span>{new Date(item.completed_at).toLocaleString("ko-KR")}</span>
              </div>
              <strong>{item.total_score ?? "-"}점</strong>
            </div>
            <div className="growth-practice-summary">
              <span>{item.purpose_label || "프로젝트 발표"}</span>
              <span>{item.practice_context?.series_name || "개별 연습"}</span>
              <strong>
                {item.score_change === null || item.score_change === undefined
                  ? "첫 기록"
                  : `이전 대비 ${item.score_change >= 0 ? "+" : ""}${item.score_change}점`}
              </strong>
            </div>
            <div className="growth-metrics">
              {Object.entries(metricLabels).map(([key, label]) => {
                const value = item.metrics?.[key] ?? 0;
                return (
                  <div key={key}>
                    <span>
                      {label} {value}
                    </span>
                    <div className="growth-track">
                      <div style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
                    </div>
                  </div>
                );
              })}
            </div>
          </article>
        ))}
      </section>
    </main>
  );
}
