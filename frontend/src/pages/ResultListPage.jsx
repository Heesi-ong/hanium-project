import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { deleteResult, getResults, updateResultMemo } from "../api/analysisApi";
import { ERROR_CODES, getErrorCode, getErrorMessage } from "../api/errorUtils";
import AnimatedSection from "../components/motion/AnimatedSection";
import CollapsibleDetails from "../components/CollapsibleDetails";
import EmptyState from "../components/EmptyState";
import ScoreTrendChart from "../components/chart/ScoreTrendChart";
import PageHeader from "../components/PageHeader";
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

// 목록 단순화(P1-04) 기준: 현재 로드된 결과가 2개 이상일 때만 추이 차트와
// 필터·정렬·비교 기능을 노출합니다. 서버 전체 건수가 더 많아도 화면에 아직 1개만
// 로드됐다면 비교할 대상이 없으므로, "더 보기" 후 실제 결과가 늘어날 때 공개합니다.
const PROGRESSIVE_FEATURES_MIN_COUNT = 2;

function getTotalScore(result) {
    const score = result?.scoreSummary?.totalScore;

    return typeof score === "number" ? score : null;
}

function compareScores(a, b, direction) {
    const aScore = getTotalScore(a);
    const bScore = getTotalScore(b);

    if (aScore === null && bScore === null) {
        return 0;
    }

    if (aScore === null) {
        return 1;
    }

    if (bScore === null) {
        return -1;
    }

    return direction === "ASC" ? aScore - bScore : bScore - aScore;
}

function ResultListPage() {
    const confirm = useConfirm();
    const navigate = useNavigate();
    const [results, setResults] = useState([]);
    const [totalResultCount, setTotalResultCount] = useState(0);
    const [filterStatus, setFilterStatus] = useState("ALL");
    const [sortType, setSortType] = useState("LATEST");
    const [keyword, setKeyword] = useState("");
    const [jobIdQuery, setJobIdQuery] = useState("");
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

    const totalCount = results.length;
    const showProgressiveFeatures = totalCount >= PROGRESSIVE_FEATURES_MIN_COUNT;

    const filteredResults = useMemo(() => {
        const normalizedKeyword = keyword.trim().toLowerCase();
        const normalizedJobIdQuery = jobIdQuery.trim().toLowerCase();

        return results
            .filter((result) => {
                if (filterStatus === "ALL") {
                    return true;
                }

                return result.status === filterStatus;
            })
            .filter((result) => {
                if (!normalizedKeyword) {
                    return true;
                }

                const searchableText = [
                    result.memo,
                    result.fileName,
                    result.originalFileName,
                    result.videoFileName,
                ]
                    .filter(Boolean)
                    .join(" ")
                    .toLowerCase();

                return searchableText.includes(normalizedKeyword);
            })
            .filter((result) => {
                if (!normalizedJobIdQuery) {
                    return true;
                }

                return (result.jobId || "").toLowerCase().includes(normalizedJobIdQuery);
            })
            .sort((a, b) => {
                if (sortType === "OLDEST") {
                    return new Date(a.createdAt || 0) - new Date(b.createdAt || 0);
                }

                if (sortType === "SCORE_DESC") {
                    return compareScores(a, b, "DESC");
                }

                if (sortType === "SCORE_ASC") {
                    return compareScores(a, b, "ASC");
                }

                return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
            });
    }, [results, filterStatus, sortType, keyword, jobIdQuery]);

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
                setTotalResultCount(
                    typeof responseData.totalElements === "number"
                        ? responseData.totalElements
                        : responseData.content.length
                );
                setHasMore(responseData.last === false);
                return;
            }

            if (Array.isArray(responseData)) {
                setResults(responseData);
                setTotalResultCount(responseData.length);
                return;
            }

            if (Array.isArray(responseData?.results)) {
                setResults(responseData.results);
                setTotalResultCount(responseData.results.length);
                return;
            }

            if (Array.isArray(responseData?.data)) {
                setResults(responseData.data);
                setTotalResultCount(responseData.data.length);
                return;
            }

            setResults([]);
            setTotalResultCount(0);
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
                if (typeof responseData.totalElements === "number") {
                    setTotalResultCount(responseData.totalElements);
                }
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
            setTotalResultCount((previous) => Math.max(0, previous - 1));
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

    function getResultTitle(result) {
        return (
            result?.memo ||
            result?.fileName ||
            result?.originalFileName ||
            result?.videoFileName ||
            "분석 결과"
        );
    }

    function getImprovementPoint(result) {
        const improvements = result?.feedback?.improvements;

        if (Array.isArray(improvements) && improvements.length > 0 && improvements[0]) {
            return improvements[0];
        }

        return "-";
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

    if (error && totalCount === 0) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result List"
                    title="분석 결과 목록"
                    description="저장된 발표 분석 결과를 다시 불러올 수 있습니다."
                />

                <EmptyState
                    title="결과 목록을 불러오지 못했습니다."
                    description={error}
                />

                <div className="button-row">
                    <button
                        type="button"
                        className="primary-button"
                        onClick={loadResults}
                    >
                        다시 시도
                    </button>
                </div>
            </section>
        );
    }

    if (totalCount === 0) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result List"
                    title="분석 결과 목록"
                    description="업로드된 발표 영상의 분석 결과를 확인하고 상세 피드백으로 이동할 수 있습니다."
                />

                <StateMessage type="error">{error}</StateMessage>

                <AnimatedSection>
                    <EmptyState
                        title="아직 분석 결과가 없습니다."
                        description="발표 영상을 업로드하면 자세, 시선, 제스처, 음성 분석 결과를 여기에서 확인할 수 있습니다."
                    />

                    <div className="button-row">
                        <Link to="/upload" className="primary-button">
                            첫 영상 업로드하기
                        </Link>

                        <Link to="/" className="secondary-button">
                            홈에서 샘플 지표 보기
                        </Link>
                    </div>
                </AnimatedSection>
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

            <div className="result-list-header">
                <h2>내 분석 결과</h2>
                <span>총 {totalResultCount}개</span>
            </div>

            {showProgressiveFeatures ? (
                <>
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

                        <input
                            type="search"
                            className="search-input"
                            placeholder="파일명 또는 메모 검색"
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

                    <CollapsibleDetails
                        className="inquiry-id-details"
                        summary="고급 검색 — 문의용 ID"
                    >
                        <input
                            type="search"
                            className="search-input"
                            placeholder="문의 시 안내받은 결과 ID(jobId)로 검색"
                            value={jobIdQuery}
                            onChange={(event) => setJobIdQuery(event.target.value)}
                        />
                    </CollapsibleDetails>

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
                </>
            ) : (
                <div className="button-row">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={loadResults}
                    >
                        새로고침
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

                                <div className="result-score-row">
                                    <div>
                                        <span>총점</span>
                                        <strong className={getScoreClassName(totalScore)}>
                                            {formatScore(totalScore)}
                                        </strong>
                                    </div>

                                    <div>
                                        <span>생성일</span>
                                        <strong>{formatDateTime(result.createdAt)}</strong>
                                    </div>
                                </div>

                                <p className="result-feedback-preview">
                                    개선 포인트: {getImprovementPoint(result)}
                                </p>

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
