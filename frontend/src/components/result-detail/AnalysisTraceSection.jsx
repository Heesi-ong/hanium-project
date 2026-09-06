// 업로드한 영상이 어떤 단계를 거쳐 분석됐는지(OpenCV 프레임/오디오 추출 → MediaPipe
// 포즈·제스처 검출 → 점수 계산) 단계별 소요 시간·처리량과 함께 보여주는 섹션입니다.
// 분석 엔진이 응답에 담아 준 analysisTrace를 그대로 렌더링합니다.

function formatDuration(milliseconds) {
    if (typeof milliseconds !== "number" || Number.isNaN(milliseconds)) {
        return "-";
    }

    if (milliseconds < 1000) {
        return `${Math.round(milliseconds)}ms`;
    }

    return `${(milliseconds / 1000).toFixed(1)}초`;
}

function AnalysisTraceSection({ analysisTrace }) {
    const steps = Array.isArray(analysisTrace) ? analysisTrace : [];

    if (steps.length === 0) {
        return null;
    }

    const recordedDurations = steps
        .map((step) => step.durationMs)
        .filter((duration) => typeof duration === "number" && !Number.isNaN(duration));
    const recordedDurationTotal = recordedDurations.reduce(
        (sum, duration) => sum + duration,
        0
    );

    return (
        <article
            className="detail-card wide result-evidence-card result-analysis-trace-card"
            aria-labelledby="analysis-trace-title"
        >
            <header className="result-evidence-card-header">
                <div>
                    <span className="result-evidence-kicker">Processing record</span>
                    <h2 id="analysis-trace-title">분석 처리 과정</h2>
                    <p>
                        분석 엔진이 반환한 단계와 처리량을 순서대로 보여줍니다.
                    </p>
                </div>
                <div className="analysis-trace-summary" aria-label={`처리 기록 ${steps.length}단계`}>
                    <strong>{steps.length}</strong>
                    <span>기록 단계</span>
                </div>
            </header>

            <div className="analysis-trace-context">
                <span>OpenCV 추출 → MediaPipe 검출 → 점수 계산</span>
                <span>
                    {recordedDurations.length > 0
                        ? `단계별 기록 시간 합계 ${formatDuration(recordedDurationTotal)}`
                        : "단계별 처리 시간 미기록"}
                </span>
            </div>

            <ol className="analysis-trace-list">
                {steps.map((step, index) => (
                    <li
                        className="analysis-trace-item"
                        key={`${step.stepNo ?? index}-${index}`}
                    >
                        <span className="analysis-trace-marker" aria-hidden="true">
                            {step.stepNo ?? index + 1}
                        </span>
                        <div className="analysis-trace-head">
                            <span className="analysis-trace-step">
                                단계 {step.stepNo ?? index + 1} / {step.totalSteps ?? steps.length}
                            </span>
                            <strong>{step.label ?? "-"}</strong>
                            <span className="analysis-trace-duration">
                                {formatDuration(step.durationMs) === "-"
                                    ? "시간 미기록"
                                    : formatDuration(step.durationMs)}
                            </span>
                        </div>

                        {step.detail && (
                            <p className="analysis-trace-detail">{step.detail}</p>
                        )}
                    </li>
                ))}
            </ol>
        </article>
    );
}

export default AnalysisTraceSection;
