import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { deleteResult, getResults, updateResultMemo } from "../api/analysisApi";
import { ERROR_CODES, getErrorCode, getErrorMessage } from "../api/errorUtils";
import AnimatedSection from "../components/motion/AnimatedSection";
import EmptyState from "../components/EmptyState";
import ScoreTrendChart from "../components/chart/ScoreTrendChart";
import PageHeader from "../components/PageHeader";
import OpenAiGenerationBadge from "../components/result-detail/OpenAiGenerationBadge";
import { firstMeaningfulResultValue } from "../components/result-detail/resultDetailFormatters";
import VideoLlmGenerationBadge from "../components/result-detail/VideoLlmGenerationBadge";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import { useConfirm } from "../context/ConfirmContext";

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
        label: "OpenAI 전체",
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
    const confirm = useConfirm();
    const navigate = useNavigate();
    const [results, setResults] = useState([]);
    const [filterStatus, setFilterStatus] = useState("ALL");
    const [generationModeFilter, setGenerationModeFilter] = useState("ALL");
    const [sortType, setSortType] = useState("LATEST");
    const [keyword, setKeyword] = useState("");
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [deletingJobId, setDeletingJobId] = useState("");
    const [error, setError] = useState("");
    const [editingMemoJobId, setEditingMemoJobId] = useState("");
    const [memoDraft, setMemoDraft] = useState("");
    const [savingMemo, setSavingMemo] = useState(false);
    const [memoError, setMemoError] = useState("");
    // 비교 모드에서는 완료된 결과 중 최대 2개까지만 선택할 수 있습니다. jobId만 저장해두고
    // 실제 비교 페이지로 넘길 때 results에서 전체 객체를 다시 찾아 state로 전달합니다.
    const [compareMode, setCompareMode] = useState(false);
    const [selectedForCompare, setSelectedForCompare] = useState([]);

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
                    result.memo,
                    result.fileName,
                    result.originalFileName,
                    result.videoFileName,
                    result.status,
                    result.statusDescription,
                    result.feedback?.generationMode,
                    result.feedback?.model,
                    result.feedback?.fallbackReason,
                    result.pipeline?.openAiGenerationMode,
                    result.pipeline?.openAiModel,
                    result.pipeline?.openAiFallbackReason,
                    result.visualAnalysis?.model?.generationMode,
                    result.visualAnalysis?.model?.name,
                    result.visualAnalysis?.model?.version,
                    result.pipeline?.videoLlmGenerationMode,
                    result.pipeline?.videoLlmAnalysis,
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
            setPage(0);
            setHasMore(false);

            const response = await getResults({ page: 0 });
            const responseData = response.data;

            if (Array.isArray(responseData?.content)) {
                setResults(responseData.content);
                setHasMore(responseData.last === false);
                return;
            }

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
            setError(getErrorMessage(
                requestError,
                "분석 결과 목록을 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }

    async function loadMoreResults() {
        try {
            setLoadingMore(true);
            setError("");

            const nextPage = page + 1;
            const response = await getResults({ page: nextPage });
            const responseData = response.data;

            if (Array.isArray(responseData?.content)) {
                setResults((prevResults) => [...prevResults, ...responseData.content]);
                setPage(nextPage);
                setHasMore(responseData.last === false);
            } else {
                setHasMore(false);
            }
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "추가 분석 결과를 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoadingMore(false);
        }
    }

    async function handleDelete(jobId) {
        if (!jobId) {
            return;
        }

        const confirmed = await confirm(
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
            if (getErrorCode(requestError) === ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED) {
                setError("본인 소유의 결과만 삭제할 수 있습니다.");
            } else {
                setError(getErrorMessage(
                    requestError,
                    "분석 결과 삭제 중 오류가 발생했습니다."
                ));
            }
        } finally {
            setDeletingJobId("");
        }
    }

    function startEditingMemo(result) {
        setEditingMemoJobId(result.jobId);
        setMemoDraft(result.memo || "");
        setMemoError("");
    }

    function cancelEditingMemo() {
        setEditingMemoJobId("");
        setMemoDraft("");
        setMemoError("");
    }

    async function handleSaveMemo(jobId) {
        try {
            setSavingMemo(true);
            setMemoError("");

            const trimmedMemo = memoDraft.trim();
            await updateResultMemo(jobId, trimmedMemo);

            setResults((prevResults) =>
                prevResults.map((result) =>
                    result.jobId === jobId
                        ? { ...result, memo: trimmedMemo || null }
                        : result
                )
            );
            setEditingMemoJobId("");
            setMemoDraft("");
        } catch (requestError) {
            setMemoError(getErrorMessage(
                requestError,
                "메모 저장 중 오류가 발생했습니다."
            ));
        } finally {
            setSavingMemo(false);
        }
    }

    function toggleCompareMode() {
        setCompareMode((previous) => !previous);
        setSelectedForCompare([]);
    }

    function toggleSelectForCompare(jobId) {
        setSelectedForCompare((previous) => {
            if (previous.includes(jobId)) {
                return previous.filter((id) => id !== jobId);
            }

            if (previous.length >= 2) {
                return previous;
            }

            return [...previous, jobId];
        });
    }

    function handleCompareNavigate() {
        const selectedResults = selectedForCompare
            .map((jobId) => results.find((result) => result.jobId === jobId))
            .filter(Boolean);

        if (selectedResults.length !== 2) {
            return;
        }

        navigate("/results/compare", { state: { results: selectedResults } });
    }

    function getGenerationMode(result) {
        return firstMeaningfulResultValue(
            result?.feedback?.generationMode,
            result?.pipeline?.openAiGenerationMode,
            "UNKNOWN"
        );
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
            result?.memo ||
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
                    loading
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

            <AnimatedSection className="result-summary-grid">
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
                    <span>OpenAI Mock</span>
                    <strong>{mockCount}</strong>
                    <p>내부 Mock 피드백으로 생성된 결과입니다.</p>
                </article>

                <article className="summary-card">
                    <span>OpenAI Real</span>
                    <strong>{realCount}</strong>
                    <p>실제 OpenAI API로 생성된 결과입니다.</p>
                </article>

                <article className="summary-card">
                    <span>OpenAI Fallback</span>
                    <strong>{fallbackCount}</strong>
                    <p>OpenAI 호출 실패 후 Mock으로 대체된 결과입니다.</p>
                </article>
            </AnimatedSection>

            <AnimatedSection>
                <ScoreTrendChart results={results} />
            </AnimatedSection>

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
                    placeholder="파일명, jobId, OpenAI/Video LLM 방식 검색"
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

                <button
                    type="button"
                    className={compareMode ? "filter-button active" : "secondary-button"}
                    onClick={toggleCompareMode}
                >
                    {compareMode ? "비교 모드 종료" : "결과 비교"}
                </button>
            </div>

            {compareMode && (
                <div className="compare-action-bar" role="status">
                    <span>
                        비교할 완료된 결과를 2개 선택하세요 ({selectedForCompare.length}/2)
                    </span>

                    <button
                        type="button"
                        className="primary-button"
                        onClick={handleCompareNavigate}
                        disabled={selectedForCompare.length !== 2}
                    >
                        선택한 결과 비교하기
                    </button>
                </div>
            )}

            <AnimatedSection>
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
                        const isSelectedForCompare = selectedForCompare.includes(result.jobId);
                        const isCompareEligible = result.status === "COMPLETED";
                        const isCompareDisabled =
                            !isSelectedForCompare && selectedForCompare.length >= 2;

                        return (
                            <article
                                className={
                                    compareMode && isSelectedForCompare
                                        ? "result-card compare-selected"
                                        : "result-card"
                                }
                                key={result.jobId}
                            >
                                <div className="result-card-header">
                                    <div>
                                        {compareMode && isCompareEligible && (
                                            <label className="compare-select-checkbox">
                                                <input
                                                    type="checkbox"
                                                    checked={isSelectedForCompare}
                                                    disabled={isCompareDisabled}
                                                    onChange={() => toggleSelectForCompare(result.jobId)}
                                                />
                                                비교 대상으로 선택
                                            </label>
                                        )}
                                        {editingMemoJobId === result.jobId ? (
                                            <div className="memo-edit-row">
                                                <input
                                                    type="text"
                                                    className="text-input"
                                                    value={memoDraft}
                                                    maxLength={200}
                                                    placeholder="예: 1차 리허설, 발표 대회 최종본"
                                                    onChange={(event) => setMemoDraft(event.target.value)}
                                                    disabled={savingMemo}
                                                    autoFocus
                                                />
                                                <button
                                                    type="button"
                                                    className="primary-button"
                                                    onClick={() => handleSaveMemo(result.jobId)}
                                                    disabled={savingMemo}
                                                >
                                                    {savingMemo ? "저장 중..." : "저장"}
                                                </button>
                                                <button
                                                    type="button"
                                                    className="secondary-button"
                                                    onClick={cancelEditingMemo}
                                                    disabled={savingMemo}
                                                >
                                                    취소
                                                </button>
                                            </div>
                                        ) : (
                                            <div className="memo-display-row">
                                                <h3>{getResultTitle(result)}</h3>
                                                <button
                                                    type="button"
                                                    className="memo-edit-button"
                                                    onClick={() => startEditingMemo(result)}
                                                    aria-label="메모 편집"
                                                >
                                                    메모 편집
                                                </button>
                                            </div>
                                        )}
                                        {editingMemoJobId === result.jobId && (
                                            <StateMessage type="error">{memoError}</StateMessage>
                                        )}
                                        <p>
                                            jobId: <code>{result.jobId}</code>
                                        </p>
                                    </div>

                                    <StatusBadge
                                        status={result.status}
                                        label={result.statusDescription || result.status}
                                    />
                                </div>

                                {result.dataIssue && (
                                    <p className="result-data-issue" role="alert">
                                        ⚠ {result.dataIssueDescription || "이 결과의 일부 데이터에 문제가 있습니다."}
                                    </p>
                                )}

                                <OpenAiGenerationBadge
                                    feedback={result.feedback}
                                    pipeline={result.pipeline}
                                />
                                <VideoLlmGenerationBadge result={result} />

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
                                    <span>표정 {formatScore(result.scoreSummary?.expressionScore)}</span>
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
            </AnimatedSection>

            {hasMore && (
                <div className="button-row">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={loadMoreResults}
                        disabled={loadingMore}
                    >
                        {loadingMore ? "불러오는 중..." : "더 보기"}
                    </button>
                </div>
            )}
        </section>
    );
}

export default ResultListPage;
