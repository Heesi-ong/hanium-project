import StateMessage from "../../components/StateMessage";

function PracticeCoachingSections({ coaching, loading, error, onRetry, onPracticeQuestion }) {
  if (!coaching) {
    return (
      <section className="card">
        {loading ? (
          <StateMessage title="연습 코칭을 불러오는 중입니다." compact />
        ) : (
          <StateMessage
            type="error"
            title="연습 코칭만 불러오지 못했습니다."
            actions={
              <button className="button" onClick={onRetry}>
                코칭 다시 불러오기
              </button>
            }
          >
            기본 분석 결과는 정상적으로 확인할 수 있습니다. {error}
          </StateMessage>
        )}
      </section>
    );
  }

  return (
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
                  {item.sentence && (
                    <small>
                      {item.start ?? "-"}초 · {item.sentence}
                    </small>
                  )}
                </div>
              ))}
            </div>
            {coaching.content_analysis.evidence.map((item) => (
              <div className="content-evidence" key={item.issue}>
                <strong>{item.issue}</strong>
                <p>
                  {item.start ?? "-"}초~{item.end ?? "-"}초 · “{item.sentence}”
                </p>
                <p>{item.rewrite_example}</p>
              </div>
            ))}
            {coaching.content_analysis.repeated_expressions?.length > 0 && (
              <p className="detail-muted-text">
                반복 표현:{" "}
                {coaching.content_analysis.repeated_expressions
                  .map((item) => `${item.expression} ${item.count}회`)
                  .join(", ")}
              </p>
            )}
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
              onClick={() => onPracticeQuestion(question)}
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
  );
}

export default PracticeCoachingSections;
