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

    return (
        <article className="detail-card practice-progress-card">
            <h2>목표 재연습</h2>

            {baselineJobId && selected && (
                <div className="practice-comparison" role="status">
                    <div>
                        <span>연습 목표</span>
                        <strong>{selected[1]}</strong>
                    </div>
                    <div>
                        <span>기준 점수</span>
                        <strong>{Number.isFinite(baselineScore) ? baselineScore : "-"}</strong>
                    </div>
                    <div>
                        <span>현재 점수</span>
                        <strong>{Number.isFinite(currentScore) ? currentScore : "-"}</strong>
                    </div>
                    <div>
                        <span>변화</span>
                        <strong className={delta >= 0 ? "practice-delta-positive" : "practice-delta-negative"}>
                            {delta === null ? "-" : `${delta > 0 ? "+" : ""}${delta}점`}
                        </strong>
                    </div>
                </div>
            )}

            {canStartPractice ? (
                <>
                    <p className="muted-text">집중해서 다시 연습할 항목을 선택하면 새 업로드가 이 결과와 연결됩니다.</p>
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
                                {label} 다시 연습
                            </button>
                        ))}
                    </div>
                </>
            ) : (
                <p className="muted-text">완료된 정상 결과에서 재연습 목표를 만들 수 있습니다.</p>
            )}
        </article>
    );
}

export default PracticeProgressSection;
