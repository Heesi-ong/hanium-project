import { Link, useLocation } from "react-router-dom";
import AnimatedSection from "../components/motion/AnimatedSection";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import OpenAiGenerationBadge from "../components/result-detail/OpenAiGenerationBadge";
import VideoLlmGenerationBadge from "../components/result-detail/VideoLlmGenerationBadge";
import ScoreCompareBarChart, { SCORE_FIELDS } from "../components/chart/ScoreCompareBarChart";

function getResultTitle(result) {
    return (
        result?.fileName ||
        result?.originalFileName ||
        result?.videoFileName ||
        "분석 결과"
    );
}

function formatDateTime(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return date.toLocaleString("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    });
}

function toScore(value) {
    return Number.isFinite(value) ? value : null;
}

function getDeltaClassName(delta) {
    if (!Number.isFinite(delta)) {
        return "compare-delta neutral";
    }

    if (delta > 0) {
        return "compare-delta positive";
    }

    if (delta < 0) {
        return "compare-delta negative";
    }

    return "compare-delta neutral";
}

function formatDelta(delta) {
    if (!Number.isFinite(delta)) {
        return "비교 불가";
    }

    if (delta > 0) {
        return `▲ +${delta}`;
    }

    if (delta < 0) {
        return `▼ ${delta}`;
    }

    return "변화 없음";
}

function formatScore(score) {
    return Number.isFinite(score) ? `${score}점` : "-";
}

function ResultComparePage() {
    const location = useLocation();
    const results = Array.isArray(location.state?.results) ? location.state.results : [];
    const [resultA, resultB] = results;

    if (!resultA || !resultB) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result Compare"
                    title="결과 비교"
                    description="비교할 두 결과를 찾을 수 없습니다."
                />

                <EmptyState
                    title="비교할 결과 정보가 없습니다."
                    description="분석 결과 목록에서 비교할 결과 2개를 선택한 뒤 다시 시도해 주세요."
                />

                <div className="button-row">
                    <Link to="/results" className="primary-button">
                        분석 결과 목록으로 이동
                    </Link>
                </div>
            </section>
        );
    }

    const scoresA = resultA.dataIssue ? {} : resultA.scoreSummary || {};
    const scoresB = resultB.dataIssue ? {} : resultB.scoreSummary || {};

    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Result Compare"
                title="결과 비교"
                description="두 분석 결과의 점수와 피드백을 나란히 비교합니다."
            />

            <AnimatedSection className="compare-header-grid">
                {[resultA, resultB].map((result, index) => (
                    <article className="result-card" key={result.jobId || index}>
                        <div className="result-card-header">
                            <div>
                                <h3>{getResultTitle(result)}</h3>
                                <p>
                                    jobId: <code>{result.jobId}</code>
                                </p>
                            </div>
                        </div>

                        <p className="result-card-header p">
                            생성일: {formatDateTime(result.createdAt)}
                        </p>

                        <OpenAiGenerationBadge
                            feedback={result.feedback}
                            pipeline={result.pipeline}
                        />
                        <VideoLlmGenerationBadge result={result} />
                    </article>
                ))}
            </AnimatedSection>

            <AnimatedSection>
                <ScoreCompareBarChart
                    resultA={resultA}
                    resultB={resultB}
                    labelA={getResultTitle(resultA)}
                    labelB={getResultTitle(resultB)}
                />
            </AnimatedSection>

            <AnimatedSection>
                <article className="detail-card wide">
                    <h2>항목별 점수 변화</h2>

                    <div className="compare-score-table">
                        <div className="compare-score-row compare-score-row-head">
                            <span>항목</span>
                            <span>{getResultTitle(resultA)}</span>
                            <span>{getResultTitle(resultB)}</span>
                            <span>변화</span>
                        </div>

                        {SCORE_FIELDS.map((field) => {
                            const scoreA = toScore(scoresA[field.key]);
                            const scoreB = toScore(scoresB[field.key]);
                            const delta = Number.isFinite(scoreA) && Number.isFinite(scoreB)
                                ? scoreB - scoreA
                                : null;

                            return (
                                <div className="compare-score-row" key={field.key}>
                                    <span>{field.label}</span>
                                    <span>{formatScore(scoreA)}</span>
                                    <span>{formatScore(scoreB)}</span>
                                    <span className={getDeltaClassName(delta)}>
                                        {formatDelta(delta)}
                                    </span>
                                </div>
                            );
                        })}
                    </div>
                </article>
            </AnimatedSection>

            {(resultA.feedback?.overall || resultB.feedback?.overall) && (
                <AnimatedSection className="compare-header-grid">
                    <article className="detail-card">
                        <h2>{getResultTitle(resultA)} 피드백</h2>
                        <p className="result-feedback-preview">
                            {resultA.feedback?.overall || "피드백이 없습니다."}
                        </p>
                    </article>

                    <article className="detail-card">
                        <h2>{getResultTitle(resultB)} 피드백</h2>
                        <p className="result-feedback-preview">
                            {resultB.feedback?.overall || "피드백이 없습니다."}
                        </p>
                    </article>
                </AnimatedSection>
            )}

            <div className="button-row">
                <Link to="/results" className="secondary-button">
                    다른 결과 비교하기
                </Link>
            </div>
        </section>
    );
}

export default ResultComparePage;
