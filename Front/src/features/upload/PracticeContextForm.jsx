// 업로드 전 발표 목적, 청중, 핵심 메시지, 연습 시리즈를 입력받는 폼이다.
function PracticeContextForm({ context, loading, practiceSeries, purposes, setContext }) {
  return (
    <div className="card">
      <h2>이번 발표의 연습 목표</h2>
      <p className="upload-description">
        목적과 청중을 알려주면 분석 결과를 실제 다음 연습 과제로 연결합니다.
      </p>
      <div className="purpose-grid" role="radiogroup" aria-label="발표 목적">
        {purposes.map((purpose) => (
          <label
            className={`purpose-card ${context.purpose === purpose.key ? "selected" : ""}`}
            key={purpose.key}
          >
            <input
              type="radio"
              name="purpose"
              value={purpose.key}
              checked={context.purpose === purpose.key}
              disabled={loading}
              onChange={(event) =>
                setContext((current) => ({
                  ...current,
                  purpose: event.target.value,
                  target_minutes: purpose.recommended_minutes,
                  series_id: null,
                  series_name: "",
                }))
              }
            />
            <strong>{purpose.label}</strong>
            <span>{purpose.focus}</span>
          </label>
        ))}
      </div>
      <div className="practice-field-grid">
        <label>
          발표 대상
          <input
            value={context.audience}
            maxLength="120"
            placeholder="예: 전공 교수님과 팀원"
            disabled={loading}
            onChange={(event) =>
              setContext((current) => ({ ...current, audience: event.target.value }))
            }
          />
        </label>
        <label>
          목표 시간(분)
          <input
            type="number"
            min="1"
            max="180"
            value={context.target_minutes}
            disabled={loading}
            onChange={(event) =>
              setContext((current) => ({
                ...current,
                target_minutes: Number(event.target.value) || 1,
              }))
            }
          />
        </label>
        <label>
          기존 연습 시리즈
          <select
            value={context.series_id || ""}
            disabled={loading}
            onChange={(event) => {
              const selected = practiceSeries.find((item) => item.series_id === event.target.value);
              setContext((current) => ({
                ...current,
                series_id: selected?.series_id || null,
                series_name: selected?.series_name || "",
              }));
            }}
          >
            <option value="">새 연습 시리즈 만들기</option>
            {practiceSeries
              .filter((item) => item.purpose === context.purpose && item.series_id)
              .map((item) => (
                <option value={item.series_id} key={item.series_id}>
                  {item.series_name}
                </option>
              ))}
          </select>
        </label>
        <label>
          새 연습 시리즈 이름
          <input
            value={context.series_name}
            maxLength="120"
            placeholder="예: 한이음 최종 발표"
            disabled={loading || Boolean(context.series_id)}
            onChange={(event) =>
              setContext((current) => ({
                ...current,
                series_name: event.target.value,
                series_id: null,
              }))
            }
          />
        </label>
        <label className="practice-core-message">
          반드시 전달할 핵심 메시지
          <textarea
            value={context.core_message}
            maxLength="500"
            rows="3"
            placeholder="청중이 발표 후 기억해야 할 한 문장을 입력하세요."
            disabled={loading}
            onChange={(event) =>
              setContext((current) => ({ ...current, core_message: event.target.value }))
            }
          />
        </label>
      </div>
    </div>
  );
}

export default PracticeContextForm;
