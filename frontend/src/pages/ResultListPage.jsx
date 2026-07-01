import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { deleteResult, getResults } from "../api/analysisApi";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import OpenAiGenerationBadge from "../components/result-detail/OpenAiGenerationBadge";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";

const FILTER_OPTIONS = [
    {
        label: "전체",
        value: "ALL",
    },
    {
        label: "완료",
        value: "COMPLETED",
    },
    {
        label: "실패",
        value: "FAILED",
    },
];

const GENERATION_MODE_FILTER_OPTIONS = [
    {
        label: "AI 전체",
        value: "ALL",
    },
    {
        label: "MOCK",
        value: "MOCK",
    },
    {
        label: "REAL",
        value: "REAL",
    },
    {
        label: "FALLBACK",
        value: "FALLBACK",
    },
    {
        label: "UNKNOWN",
        value: "UNKNOWN",
    },
    {
        label: "FAILED",
        value: "FAILED",
    },
];

const SORT_OPTIONS = [
    {
        label: "최신순",
        value: "LATEST",
    },
    {
        label: "오래된순",
        value: "OLDEST",
    },
    {
        label: "점수 높은순",
        value: "SCORE_DESC",
    },
    {
        label: "점수 낮은순",
        value: "SCORE_ASC",
    },
];

function ResultListPage() {
    const [results, setResults] = useState([]);
    const [filterStatus, setFilterStatus] = useState("ALL");
    const [generationModeFilter, setGenerationModeFilter] = useState("ALL");
    const [sortType, setSortType] = useState("LATEST");
    const [keyword, setKeyword] = useState("");
    const [loading, setLoading] = useState(true);
    const [deletingJobId, setDeletingJobId] = useState("");
    const [error, setError] = useState("");

    const completedCount = results.filter(
        (result) => result.status === "COMPLETED"
    ).length;

    const failedCount = results.filter(
        (result) => result.status === "FAILED"
    ).length;

    const mockCount = results.filter(
        (result) => getGenerationMode(result) === "MOCK"
    ).length;

    const realCount = results.filter(
        (result) => getGenerationMode(result) === "REAL"
    ).length;

    const fallbackCount = results.filter(
        (result) => getGenerationMode(result) === "FALLBACK"
    ).length;

    const filteredResults = useMemo(() => {
        const normalizedKeyword = keyword.trim().toLowerCase();

        return results
            .filter((result) => {
                if (filterStatus === "ALL") {
                    return true;
                }

                return result.status === filterStatus;
            })
            .filter((result) => {
                if (generationModeFilter === "ALL") {
                    return true;
                }

                return getGenerationMode(result) === generationModeFilter;
            })
            .filter((result) => {
                if (!normalizedKeyword) {
                    return true;
                }

                const searchableText = [
                    result.jobId,
                    result.fileName,
                    result.originalFileName,
                    result.videoFileName,
                    result.status,
                    result.statusDescription,
                    result.feedback?.generationMode,
                    result.feedback?.model,
                    result.feedback?.fallbackReason,
                ]
                    .filter(Boolean)
                    .join(" ")
                    .toLowerCase();

                return searchableText.includes(normalizedKeyword);
            })
            .sort((a, b) => {
                if (sortType === "OLDEST") {
                    return new Date(a.createdAt || 0) - new Date(b.createdAt || 0);
                }

                if (sortType === "SCORE_DESC") {
                    return getTotalScore(b) - getTotalScore(a);
                }

                if (sortType === "SCORE_ASC") {
                    return getTotalScore(a) - getTotalScore(b);
                }

                return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
            });
    }, [results, filterStatus, generationModeFilter, sortType, keyword]);

    useEffect(() => {
        loadResults();
    }, []);

    async function loadResults() {
        try {
            setLoading(true);
            setError("");

            const response = await getResults();
            const responseData = response.data;

            if (Array.isArray(responseData)) {
                setResults(responseData);
                return;
            }

            if (Array.isArray(responseData?.results)) {
                setResults(responseData.results);
                return;
            }

            if (Array.isArray(responseData?.data)) {
                setResults(responseData.data);
                return;
            }

            setResults([]);
        } catch (requestError) {
            setError(
                requestError.message ||
                "분석 결과 목록을 불러오는 중 오류가 발생했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    async function handleDelete(jobId) {
        if (!jobId) {
            return;
        }

        const confirmed = window.confirm(
            "이 분석 결과를 삭제하시겠습니까? 업로드 영상과 결과 JSON 파일도 함께 삭제됩니다."
        );

        if (!confirmed) {
            return;
        }

        try {
            setDeletingJobId(jobId);
            setError("");

            await deleteResult(jobId);

            setResults((prevResults) =>
                prevResults.filter((result) => result.jobId !== jobId)
            );
        } catch (requestError) {
            setError(
                requestError.message ||
                "분석 결과 삭제 중 오류가 발생했습니다."
            );
        } finally {
            setDeletingJobId("");
        }
    }

    function getGenerationMode(result) {
        return result?.feedback?.generationMode || "UNKNOWN";
    }

    function getTotalScore(result) {
        const score = result?.scoreSummary?.totalScore;

        if (typeof score === "number") {
            return score;
        }

        return 0;
    }

    function getResultTitle(result) {
        return (
            result?.fileName ||
            result?.originalFileName ||
            result?.videoFileName ||
            "분석 결과"
        );
    }

    function getScoreClassName(score) {
        if (typeof score !== "number") {
            return "score-badge score-muted";
        }

        if (score >= 85) {
            return "score-badge score-good";
        }

        if (score >= 70) {
            return "score-badge score-normal";
        }

        if (score >= 50) {
            return "score-badge score-warning";
        }

        return "score-badge score-bad";
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

    function formatScore(value) {
        if (value === null || value === undefined) {
            return "-";
        }

        return value;
    }

    if (loading) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result List"
                    title="분석 결과 목록"
                    description="저장된 발표 분석 결과를 불러오는 중입니다."
                />

                <EmptyState
                    title="로딩 중"
                    description="잠시만 기다려 주세요."
                />
            </section>
        );
    }

    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Result List"
                title="분석 결과 목록"
                description="업로드된 발표 영상의 분석 결과를 확인하고 상세 피드백으로 이동할 수 있습니다."
            />

            <StateMessage type="error">{error}</StateMessage>

            <div className="result-summary-grid">
                <article className="summary-card">
                    <span>전체 결과</span>
                    <strong>{results.length}</strong>
                    <p>저장된 전체 분석 결과 수입니다.</p>
                </article>

                <article className="summary-card">
                    <span>완료</span>
                    <strong>{completedCount}</strong>
                    <p>정상적으로 분석이 완료된 결과입니다.</p>
                </article>

                <article className="summary-card">
                    <span>실패</span>
                    <strong>{failedCount}</strong>
                    <p>분석 중 오류가 발생한 결과입니다.</p>
                </article>

                <article className="summary-card">
                    <span>Mock</span>
                    <strong>{mockCount}</strong>
                    <p>내부 Mock 피드백으로 생성된 결과입니다.</p>
                </article>

                <article className="summary-card">
                    <span>Real</span>
                    <strong>{realCount}</strong>
                    <p>실제 OpenAI API로 생성된 결과입니다.</p>
                </article>

                <article className="summary-card">
                    <span>Fallback</span>
                    <strong>{fallbackCount}</strong>
                    <p>OpenAI 호출 실패 후 Mock으로 대체된 결과입니다.</p>
                </article>
            </div>

            <div className="result-control-grid">
                <div className="filter-button-group">
                    {FILTER_OPTIONS.map((option) => (
                        <button
                            key={option.value}
                            type="button"
                            className={
                                filterStatus === option.value
                                    ? "filter-button active"
                                    : "filter-button"
                            }
                            onClick={() => setFilterStatus(option.value)}
                        >
                            {option.label}
                        </button>
                    ))}
                </div>

                <div className="filter-button-group">
                    {GENERATION_MODE_FILTER_OPTIONS.map((option) => (
                        <button
                            key={option.value}
                            type="button"
                            className={
                                generationModeFilter === option.value
                                    ? "filter-button active"
                                    : "filter-button"
                            }
                            onClick={() => setGenerationModeFilter(option.value)}
                        >
                            {option.label}
                        </button>
                    ))}
                </div>

                <input
                    type="search"
                    className="search-input"
                    placeholder="파일명, jobId, 생성 방식 검색"
                    value={keyword}
                    onChange={(event) => setKeyword(event.target.value)}
                />

                <select
                    className="sort-select"
                    value={sortType}
                    onChange={(event) => setSortType(event.target.value)}
                >
                    {SORT_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                            {option.label}
                        </option>
                    ))}
                </select>

                <button
                    type="button"
                    className="secondary-button"
                    onClick={loadResults}
                >
                    새로고침
                </button>
            </div>

            {filteredResults.length === 0 ? (
                <EmptyState
                    title="표시할 분석 결과가 없습니다."
                    description="검색 조건을 변경하거나 새 영상을 업로드해 주세요."
                />
            ) : (
                <div className="result-list">
                    {filteredResults.map((result) => {
                        const totalScore = getTotalScore(result);
                        const isDeleting = deletingJobId === result.jobId;

                        return (
                            <article className="result-card" key={result.jobId}>
                                <div className="result-card-header">
                                    <div>
                                        <h3>{getResultTitle(result)}</h3>
                                        <p>
                                            jobId: <code>{result.jobId}</code>
                                        </p>
                                    </div>

                                    <StatusBadge
                                        status={result.status}
                                        label={result.statusDescription || result.status}
                                    />
                                </div>

                                <OpenAiGenerationBadge feedback={result.feedback} />

                                <div className="result-score-row">
                                    <div>
                                        <span>총점</span>
                                        <strong className={getScoreClassName(totalScore)}>
                                            {formatScore(totalScore)}
                                        </strong>
                                    </div>

                                    <div>
                                        <span>등급</span>
                                        <strong>{result.scoreSummary?.level || "-"}</strong>
                                    </div>

                                    <div>
                                        <span>생성일</span>
                                        <strong>{formatDateTime(result.createdAt)}</strong>
                                    </div>
                                </div>

                                <div className="result-metric-row">
                                    <span>자세 {formatScore(result.scoreSummary?.postureScore)}</span>
                                    <span>시선 {formatScore(result.scoreSummary?.gazeScore)}</span>
                                    <span>음성 {formatScore(result.scoreSummary?.speechScore)}</span>
                                    <span>제스처 {formatScore(result.scoreSummary?.gestureScore)}</span>
                                    <span>표정 {formatScore(result.scoreSummary?.emotionScore)}</span>
                                </div>

                                {result.feedback?.overall && (
                                    <p className="result-feedback-preview">
                                        {result.feedback.overall}
                                    </p>
                                )}

                                <div className="result-card-actions">
                                    <Link
                                        to={`/results/${result.jobId}`}
                                        className="primary-button"
                                    >
                                        상세 보기
                                    </Link>

                                    <button
                                        type="button"
                                        className="danger-button"
                                        onClick={() => handleDelete(result.jobId)}
                                        disabled={isDeleting}
                                    >
                                        {isDeleting ? "삭제 중..." : "삭제"}
                                    </button>
                                </div>
                            </article>
                        );
                    })}
                </div>
            )}
        </section>
    );
}

export default ResultListPage;