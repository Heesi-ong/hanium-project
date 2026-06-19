// 저장된 AI 코칭을 표시하고 사용자가 AI 코칭 생성을 요청할 수 있게 한다.
import StateMessage from "../../components/StateMessage";
import "./AiCoachingSection.css";

function AiCoachingSection({
  aiCoaching,
  loading,
  error,
  onGenerate,
  onRegenerate,
  onContinueChat,
  onPracticeQuestion,
}) {
  const coaching = aiCoaching?.coaching;

  return (
    <section className="card ai-coaching-card">
      <div className="detail-section-header">
        <div>
          <p className="detail-kicker">Optional Ollama Coaching</p>
          <h2>AI 발표 코칭</h2>
          <p className="detail-muted-text">
            시스템이 계산한 점수는 변경하지 않고 발표 목적과 검증 가능한 분석 근거를 해석합니다.
          </p>
        </div>
        <div className="detail-action-row">
          {coaching ? (
            <>
              <button className="button secondary" onClick={onRegenerate} disabled={loading}>
                코칭 다시 생성
              </button>
              <button className="button" onClick={onContinueChat}>
                AI 코치 대화로 이어가기
              </button>
            </>
          ) : (
            <button className="button" onClick={onGenerate} disabled={loading}>
              AI 코칭 생성
            </button>
          )}
        </div>
      </div>

      {loading ? (
        <StateMessage title="Ollama가 발표 분석 결과를 해석하고 있습니다." compact />
      ) : error ? (
        <StateMessage
          type="error"
          title="AI 코칭 영역만 처리하지 못했습니다."
          actions={
            <button className="button" onClick={onGenerate}>
              AI 코칭 다시 시도
            </button>
          }
        >
          기본 분석 결과와 규칙 기반 코칭은 정상적으로 확인할 수 있습니다. {error}
        </StateMessage>
      ) : !coaching ? (
        <p className="detail-muted-text">
          필요할 때 생성 버튼을 눌러 현재 발표에 대한 선택형 Ollama 코칭을 받을 수 있습니다.
        </p>
      ) : (
        <div className="ai-coaching-content">
          {aiCoaching.status === "fallback" && (
            <div className="ai-coaching-warning">
              Ollama 코칭을 생성하지 못해 규칙 기반 대체 코칭을 표시합니다.
              {aiCoaching.failure_reason ? ` 사유: ${aiCoaching.failure_reason}` : ""}
            </div>
          )}

          <article>
            <h3>종합 해석</h3>
            <p>{coaching.summary}</p>
          </article>

          {coaching.strengths?.length > 0 && (
            <article>
              <h3>잘한 점</h3>
              <div className="ai-coaching-grid">
                {coaching.strengths.map((item, index) => (
                  <div className="ai-coaching-item" key={`${item.title}-${index}`}>
                    <strong>{item.title}</strong>
                    <span>{item.evidence}</span>
                  </div>
                ))}
              </div>
            </article>
          )}

          <article>
            <h3>우선 개선사항</h3>
            <div className="ai-coaching-grid">
              {coaching.priorities?.map((item, index) => (
                <div className="ai-coaching-item" key={`${item.title}-${index}`}>
                  <strong>
                    {index + 1}. {item.title}
                  </strong>
                  <p>{item.reason}</p>
                  <span>
                    <b>근거:</b> {item.evidence}
                  </span>
                  <span>
                    <b>다음 행동:</b> {item.action}
                  </span>
                  <span>
                    <b>연습 과제:</b> {item.exercise}
                  </span>
                  <span>
                    <b>수정 예시:</b> {item.rewrite_example}
                  </span>
                </div>
              ))}
            </div>
          </article>

          {coaching.expected_questions?.length > 0 && (
            <article>
              <h3>예상 질문</h3>
              <div className="ai-question-list">
                {coaching.expected_questions.map((question) => (
                  <button
                    className="button secondary"
                    key={question}
                    onClick={() => onPracticeQuestion(question)}
                  >
                    {question}
                  </button>
                ))}
              </div>
            </article>
          )}

          {coaching.limitations?.length > 0 && (
            <article>
              <h3>분석 신뢰도와 한계</h3>
              <ul>
                {coaching.limitations.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>
          )}

          {aiCoaching.knowledge_sources?.length > 0 && (
            <article>
              <h3>RAG 참고 지식</h3>
              <p className="detail-muted-text">
                아래 문서는 코칭 방향을 구성하는 참고 자료이며 시스템 측정 근거와는 구분됩니다.
              </p>
              <div className="detail-tag-area">
                {aiCoaching.knowledge_sources.map((source) => (
                  <span className="tag" key={source.id}>
                    {source.title} · v{source.version}
                  </span>
                ))}
              </div>
            </article>
          )}

          <p className="detail-muted-text">
            모델 {aiCoaching.model} · 프롬프트 {aiCoaching.prompt_version}
          </p>
        </div>
      )}
    </section>
  );
}

export default AiCoachingSection;
