import { Link, useLocation } from "react-router-dom";
import AnimatedSection from "../components/motion/AnimatedSection";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import PageFadeIn from "../components/motion/PageFadeIn";
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
            <PageFadeIn className="page-section result-compare-page">
                <div className="compare-hero">
                    <PageHeader
                        eyebrow="Result Compare"
                        title="결과 비교"
                        description="비교할 두 결과를 찾을 수 없습니다."
                    />
                </div>

                <div className="compare-empty-panel">
                    <span className="compare-empty-symbol" aria-hidden="true">A ↔ B</span>
                    <EmptyState
                        title="비교할 결과 정보가 없습니다."
                        description="분석 결과 목록에서 비교할 결과 2개를 선택한 뒤 다시 시도해 주세요."
                    />

                    <div className="button-row">
                        <Link to="/results" className="primary-button">
                            분석 결과 목록으로 이동
                        </Link>
                    </div>
                </div>
            </PageFadeIn>
        );
    }

    const scoresA = resultA.dataIssue ? {} : resultA.scoreSummary || {};
    const scoresB = resultB.dataIssue ? {} : resultB.scoreSummary || {};

    return (
        <PageFadeIn className="page-section result-compare-page">
            <div className="compare-hero">
                <PageHeader
                    eyebrow="Result Compare"
                    title="결과 비교"
                    description="두 분석 결과의 점수와 피드백을 나란히 비교합니다."
                />
                <div className="compare-direction" aria-label="점수 변화 계산 방향">
                    <span><b>A</b> 기준 결과</span>
                    <span aria-hidden="true">→</span>
                    <span><b>B</b> 비교 결과</span>
                </div>
                <p className="compare-direction-note">
                    변화는 B 점수 − A 점수입니다. 선택 순서를 유지하며, 생성일순으로 자동 정렬하지 않습니다.
                </p>
            </div>

            <AnimatedSection className="compare-header-grid">
                {[resultA, resultB].map((result, index) => (
                    <article className="result-card compare-source-card" data-side={index === 0 ? "a" : "b"} key={result.jobId || index}>
                        <span className="compare-source-label">
                            <b>{index === 0 ? "A" : "B"}</b>
                            {index === 0 ? "기준 결과" : "비교 결과"}
                        </span>
                        <div className="result-card-header">
                            <div>
                                <h2>{getResultTitle(result)}</h2>
                                <p className="compare-job-id">
                                    jobId: <code>{result.jobId}</code>
                                </p>
                            </div>
                        </div>

                        <p className="compare-created-at">
                            생성일: {formatDateTime(result.createdAt)}
                        </p>

                        <OpenAiGenerationBadge
                            feedback={result.feedback}
                            pipeline={result.pipeline}
                        />
                        <VideoLlmGenerationBadge result={result} />
                        {result.dataIssue && (
                            <p className="compare-data-warning" role="status">
                                점수 데이터를 확인할 수 없어 이 결과의 점수는 비교에서 제외됩니다.
                            </p>
                        )}
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

                    <p className="compare-table-note" id="compare-score-guidance">
                        숫자가 없는 항목은 0점이 아닌 데이터 없음이며, 변화도 계산하지 않습니다.
                    </p>
                    <table className="compare-score-table" aria-describedby="compare-score-guidance">
                        <caption>두 결과의 항목별 점수와 B − A 변화</caption>
                        <thead>
                            <tr>
                                <th scope="col">항목</th>
                                <th scope="col">A · 기준 결과</th>
                                <th scope="col">B · 비교 결과</th>
                                <th scope="col">변화</th>
                            </tr>
                        </thead>
                        <tbody>
                            {SCORE_FIELDS.map((field) => {
                                const scoreA = toScore(scoresA[field.key]);
                                const scoreB = toScore(scoresB[field.key]);
                                const delta = Number.isFinite(scoreA) && Number.isFinite(scoreB)
                                    ? scoreB - scoreA
                                    : null;

                                return (
                                    <tr key={field.key}>
                                        <th scope="row">{field.label}</th>
                                        <td>{formatScore(scoreA)}</td>
                                        <td>{formatScore(scoreB)}</td>
                                        <td className={getDeltaClassName(delta)}>
                                            {formatDelta(delta)}
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </article>
            </AnimatedSection>

            {(resultA.feedback?.overall || resultB.feedback?.overall) && (
                <AnimatedSection className="compare-header-grid">
                    <article className="detail-card">
                        <span className="compare-source-label"><b>A</b> 기준 결과 피드백</span>
                        <h2>{getResultTitle(resultA)} 피드백</h2>
                        <p className="result-feedback-preview">
                            {resultA.feedback?.overall || "피드백이 없습니다."}
                        </p>
                    </article>

                    <article className="detail-card">
                        <span className="compare-source-label"><b>B</b> 비교 결과 피드백</span>
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
        </PageFadeIn>
    );
}

export default ResultComparePage;
