import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import { deleteResult, getResults } from "../api/analysisApi";

const AUTO_REFRESH_INTERVAL_MS = 3000;

const RUNNING_STATUSES = [
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
];

function ResultListPage() {
    const autoRefreshTimerRef = useRef(null);

    const [results, setResults] = useState([]);
    const [loading, setLoading] = useState(true);
    const [autoRefreshing, setAutoRefreshing] = useState(false);
    const [deletingJobId, setDeletingJobId] = useState("");
    const [error, setError] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [searchKeyword, setSearchKeyword] = useState("");

    const hasRunningJob = useMemo(() => {
        return results.some((result) => RUNNING_STATUSES.includes(result.status));
    }, [results]);

    const filteredResults = useMemo(() => {
        return results.filter((result) => {
            const matchesStatus =
                statusFilter === "ALL" ||
                result.status === statusFilter ||
                (statusFilter === "RUNNING" && RUNNING_STATUSES.includes(result.status));

            const keyword = searchKeyword.trim().toLowerCase();

            const originalFileName = result.originalFileName || "";
            const jobId = result.jobId || "";

            const matchesKeyword =
                keyword.length === 0 ||
                jobId.toLowerCase().includes(keyword) ||
                originalFileName.toLowerCase().includes(keyword);

            return matchesStatus && matchesKeyword;
        });
    }, [results, statusFilter, searchKeyword]);

    const totalCount = results.length;

    const uploadedCount = results.filter(
        (result) => result.status === "UPLOADED"
    ).length;

    const runningCount = results.filter((result) =>
        RUNNING_STATUSES.includes(result.status)
    ).length;

    const completedCount = results.filter(
        (result) => result.status === "COMPLETED"
    ).length;

    const failedCount = results.filter(
        (result) => result.status === "FAILED"
    ).length;

    useEffect(() => {
        loadResults();

        return () => {
            stopAutoRefresh();
        };
    }, []);

    useEffect(() => {
        if (hasRunningJob) {
            startAutoRefresh();
            return;
        }

        stopAutoRefresh();
    }, [hasRunningJob]);

    function startAutoRefresh() {
        if (autoRefreshTimerRef.current) {
            return;
        }

        setAutoRefreshing(true);

        autoRefreshTimerRef.current = setInterval(() => {
            loadResults({ silent: true });
        }, AUTO_REFRESH_INTERVAL_MS);
    }

    function stopAutoRefresh() {
        if (autoRefreshTimerRef.current) {
            clearInterval(autoRefreshTimerRef.current);
            autoRefreshTimerRef.current = null;
        }

        setAutoRefreshing(false);
    }

    async function loadResults(options = {}) {
        const silent = options.silent === true;

        try {
            if (!silent) {
                setLoading(true);
            }

            setError("");

            const response = await getResults();

            setResults(response.data || []);
        } catch (requestError) {
            setError(
                requestError.message ||
                "분석 결과 목록을 불러오는 중 오류가 발생했습니다."
            );
        } finally {
            if (!silent) {
                setLoading(false);
            }
        }
    }

    async function handleManualRefresh() {
        await loadResults();
    }

    async function handleDelete(jobId) {
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

            await loadResults({ silent: true });
        } catch (requestError) {
            setError(
                requestError.message ||
                "분석 결과 삭제 중 오류가 발생했습니다."
            );
        } finally {
            setDeletingJobId("");
        }
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

    function formatFileSize(fileSize) {
        if (!fileSize && fileSize !== 0) {
            return "-";
        }

        return `${(fileSize / 1024 / 1024).toFixed(2)}MB`;
    }

    function getStatusText(result) {
        return result.statusDescription || result.status || "-";
    }

    function isDeleteDisabled(result) {
        return deletingJobId === result.jobId || RUNNING_STATUSES.includes(result.status);
    }

    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Results"
                title="분석 결과 목록"
                description="업로드된 발표 영상의 분석 상태, 파일 정보, 완료 시간을 확인하고 상세 결과로 이동할 수 있습니다."
            />

            <div className="summary-grid">
                <article className="summary-card">
                    <span>전체 분석</span>
                    <strong>{totalCount}</strong>
                </article>

                <article className="summary-card">
                    <span>업로드 완료</span>
                    <strong>{uploadedCount}</strong>
                </article>

                <article className="summary-card">
                    <span>진행 중</span>
                    <strong>{runningCount}</strong>
                </article>

                <article className="summary-card">
                    <span>완료</span>
                    <strong>{completedCount}</strong>
                </article>

                <article className="summary-card">
                    <span>실패</span>
                    <strong>{failedCount}</strong>
                </article>
            </div>

            <div className="result-toolbar">
                <div className="filter-group">
                    <button
                        type="button"
                        className={statusFilter === "ALL" ? "filter-button active" : "filter-button"}
                        onClick={() => setStatusFilter("ALL")}
                    >
                        전체
                    </button>

                    <button
                        type="button"
                        className={
                            statusFilter === "UPLOADED" ? "filter-button active" : "filter-button"
                        }
                        onClick={() => setStatusFilter("UPLOADED")}
                    >
                        업로드 완료
                    </button>

                    <button
                        type="button"
                        className={
                            statusFilter === "RUNNING" ? "filter-button active" : "filter-button"
                        }
                        onClick={() => setStatusFilter("RUNNING")}
                    >
                        진행 중
                    </button>

                    <button
                        type="button"
                        className={
                            statusFilter === "COMPLETED" ? "filter-button active" : "filter-button"
                        }
                        onClick={() => setStatusFilter("COMPLETED")}
                    >
                        완료
                    </button>

                    <button
                        type="button"
                        className={
                            statusFilter === "FAILED" ? "filter-button active" : "filter-button"
                        }
                        onClick={() => setStatusFilter("FAILED")}
                    >
                        실패
                    </button>
                </div>

                <input
                    className="search-input"
                    type="search"
                    placeholder="jobId 또는 파일명 검색"
                    value={searchKeyword}
                    onChange={(event) => setSearchKeyword(event.target.value)}
                />
            </div>

            <StateMessage type="polling">
                {autoRefreshing
                    ? `진행 중인 분석 작업이 있어 ${AUTO_REFRESH_INTERVAL_MS / 1000}초마다 목록을 자동 갱신합니다.`
                    : ""}
            </StateMessage>

            <StateMessage type="error">{error}</StateMessage>

            <div className="result-list-card">
                <div className="result-list-header">
                    <h2>분석 작업 목록</h2>

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={handleManualRefresh}
                        disabled={loading}
                    >
                        {loading ? "새로고침 중..." : "새로고침"}
                    </button>
                </div>

                {loading ? (
                    <EmptyState title="분석 결과 목록을 불러오는 중입니다." />
                ) : filteredResults.length === 0 ? (
                    <EmptyState
                        title="표시할 분석 결과가 없습니다."
                        description="영상을 업로드하고 분석을 실행하면 이곳에 결과가 표시됩니다."
                    />
                ) : (
                    <div className="result-list">
                        {filteredResults.map((result) => (
                            <article className="result-item" key={result.jobId}>
                                <div className="result-item-main">
                                    <div className="result-item-title-row">
                                        <h3>{result.originalFileName}</h3>

                                        <StatusBadge
                                            status={result.status}
                                            label={getStatusText(result)}
                                        />
                                    </div>

                                    <div className="result-meta-grid">
                                        <div>
                                            <span>Job ID</span>
                                            <strong>{result.jobId}</strong>
                                        </div>

                                        <div>
                                            <span>파일 크기</span>
                                            <strong>{formatFileSize(result.fileSize)}</strong>
                                        </div>

                                        <div>
                                            <span>생성 시간</span>
                                            <strong>{formatDateTime(result.createdAt)}</strong>
                                        </div>

                                        <div>
                                            <span>완료 시간</span>
                                            <strong>{formatDateTime(result.completedAt)}</strong>
                                        </div>
                                    </div>
                                </div>

                                <div className="result-item-actions">
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
                                        disabled={isDeleteDisabled(result)}
                                    >
                                        {deletingJobId === result.jobId ? "삭제 중..." : "삭제"}
                                    </button>
                                </div>
                            </article>
                        ))}
                    </div>
                )}
            </div>
        </section>
    );
}

export default ResultListPage;