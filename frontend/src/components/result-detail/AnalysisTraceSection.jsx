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

    const totalMilliseconds = steps.reduce(
        (sum, step) =>
            sum + (typeof step.durationMs === "number" ? step.durationMs : 0),
        0
    );

    return (
        <article className="detail-card wide">
            <h2>분석 처리 과정</h2>

            <p className="muted-text">
                업로드한 영상은 OpenCV로 장면 프레임과 오디오를 추출하고, MediaPipe Pose
                Landmarker로 자세와 제스처를 검출한 뒤 점수를 계산합니다. 아래는 이번
                분석에서 각 단계가 실제로 처리한 내용과 걸린 시간입니다. (총 약{" "}
                {formatDuration(totalMilliseconds)})
            </p>

            <ol className="analysis-trace-list">
                {steps.map((step, index) => (
                    <li
                        className="analysis-trace-item"
                        key={`${step.stepNo ?? index}-${index}`}
                    >
                        <div className="analysis-trace-head">
                            <span className="analysis-trace-step">
                                {step.stepNo ?? index + 1}/
                                {step.totalSteps ?? steps.length}
                            </span>
                            <strong>{step.label ?? "-"}</strong>
                            <span className="analysis-trace-duration">
                                {formatDuration(step.durationMs)}
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
