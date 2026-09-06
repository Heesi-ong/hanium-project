const GOALS = [
    ["POSTURE", "자세", "postureScore"],
    ["SPEECH", "음성", "speechScore"],
    ["GESTURE", "제스처", "gestureScore"],
];

function PracticeProgressSection({
    currentJobId,
    currentScoreSummary,
    baselineJobId,
    baselineScoreSummary,
    practiceGoal,
    canStartPractice,
    onStartPractice,
}) {
    const selected = GOALS.find(([goal]) => goal === practiceGoal);
    const currentScore = selected ? currentScoreSummary?.[selected[2]] : null;
    const baselineScore = selected ? baselineScoreSummary?.[selected[2]] : null;
    const canCompare = Number.isFinite(currentScore) && Number.isFinite(baselineScore);
    const delta = canCompare ? currentScore - baselineScore : null;
    const deltaClassName = delta === null
        ? "unknown"
        : delta > 0
            ? "positive"
            : delta < 0
                ? "negative"
                : "neutral";
    const deltaLabel = delta === null
        ? "비교 불가"
        : delta > 0
            ? "향상"
            : delta < 0
                ? "하락"
                : "변화 없음";

    return (
        <article
            className="detail-card result-practice-card practice-progress-card"
            aria-labelledby="practice-progress-title"
        >
            <header className="result-practice-card-header">
                <div>
                    <span className="result-feedback-kicker">Next upload</span>
                    <h2 id="practice-progress-title">목표 재연습</h2>
                    <p>개선할 영역을 하나 선택해 현재 결과와 연결된 새 연습을 시작하세요.</p>
                </div>
                <span className={`mini-badge ${canStartPractice ? "success" : "muted"}`}>
                    {canStartPractice ? "시작 가능" : "현재 사용 불가"}
                </span>
            </header>

            {baselineJobId && selected && (
                <div
                    className="practice-comparison result-practice-comparison"
                    role="status"
                    aria-label={`${selected[1]} 재연습 점수 비교`}
                >
                    <div className="result-practice-comparison-heading">
                        <span>이전 연습 대비</span>
                        <strong>{selected[1]} 목표</strong>
                    </div>
                    <div>
                        <span>이전 점수</span>
                        <strong>{Number.isFinite(baselineScore) ? baselineScore : "-"}</strong>
                    </div>
                    <div>
                        <span>현재 점수</span>
                        <strong>{Number.isFinite(currentScore) ? currentScore : "-"}</strong>
                    </div>
                    <div className={`result-practice-delta ${deltaClassName}`}>
                        <span>{deltaLabel}</span>
                        <strong>
                            {delta === null ? "-" : `${delta > 0 ? "+" : ""}${delta}점`}
                        </strong>
                    </div>
                </div>
            )}

            {canStartPractice ? (
                <div className="result-practice-action">
                    <div className="result-practice-action-copy">
                        <strong>어떤 영역부터 다시 연습할까요?</strong>
                        <p>선택한 목표와 현재 결과 ID가 업로드 화면으로 안전하게 전달됩니다.</p>
                    </div>
                    <div className="practice-goal-buttons no-print">
                        {GOALS.map(([goal, label]) => (
                            <button
                                type="button"
                                className="secondary-button"
                                key={goal}
                                onClick={() => onStartPractice({
                                    baselineJobId: currentJobId,
                                    practiceGoal: goal,
                                    label,
                                })}
                            >
                                <span aria-hidden="true">→</span>
                                {label} 다시 연습
                            </button>
                        ))}
                    </div>
                </div>
            ) : (
                <div className="result-practice-empty" role="note">
                    <span aria-hidden="true">i</span>
                    <div>
                        <strong>이 결과에서는 재연습을 시작할 수 없습니다.</strong>
                        <p>완료된 정상 결과에서 재연습 목표를 만들 수 있습니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default PracticeProgressSection;
