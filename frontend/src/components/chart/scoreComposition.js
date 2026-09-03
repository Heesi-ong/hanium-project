const CONTRIBUTION_FIELDS = [
    { key: "postureScore", label: "자세 기여" },
    { key: "speechScore", label: "음성 기여" },
    { key: "gestureScore", label: "제스처 기여" },
];

function toFinite(value) {
    return Number.isFinite(value) ? value : null;
}

function roundChartValue(value) {
    return Math.round(value * 10000) / 10000;
}

export function buildScoreComposition(scoreSummary, scoreExplanation) {
    const contributions = scoreExplanation?.weightedContributions || {};
    const values = CONTRIBUTION_FIELDS.map((field) => toFinite(contributions[field.key]));
    const rawScore = toFinite(scoreExplanation?.rawScore);
    const totalScore = toFinite(scoreSummary?.totalScore);
    const penalty = toFinite(scoreExplanation?.penaltyApplied) || 0;

    if (values.some((value) => value === null) || rawScore === null || totalScore === null) {
        return null;
    }

    let cumulative = 0;
    const contributionBars = values.map((value) => {
        const start = cumulative;
        cumulative = roundChartValue(cumulative + value);
        return [start, cumulative];
    });

    return {
        labels: [
            ...CONTRIBUTION_FIELDS.map((field) => field.label),
            "가중 원점수",
            "신뢰도 감점",
            "최종 점수",
        ],
        values: [
            ...contributionBars,
            [0, rawScore],
            [Math.max(totalScore, 0), Math.max(rawScore, totalScore)],
            [0, totalScore],
        ],
        contributions: Object.fromEntries(
            CONTRIBUTION_FIELDS.map((field, index) => [field.key, values[index]])
        ),
        rawScore,
        penalty,
        totalScore,
    };
}
