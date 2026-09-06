import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import { deleteAdminResult, getAdminUserResults } from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import AdminNav from "../components/admin/AdminNav";
import EmptyState from "../components/EmptyState";
import OpenAiGenerationBadge from "../components/result-detail/OpenAiGenerationBadge";
import VideoLlmGenerationBadge from "../components/result-detail/VideoLlmGenerationBadge";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import { EASE_OUT } from "../components/motion/animationVariants";
import { useReasonPrompt } from "../context/ConfirmContext";

function AdminUserDetailPage() {
    const { userId } = useParams();
    const promptReason = useReasonPrompt();
    const prefersReducedMotion = useReducedMotion();
    const [actionJobId, setActionJobId] = useState("");
    const [results, setResults] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    const loadResults = useCallback(async () => {
        try {
            setLoading(true);
            setError("");
            setSuccess("");
            setPage(0);
            setHasMore(false);

            const response = await getAdminUserResults(userId, { page: 0 });
            const responseData = response.data;

            setResults(responseData?.content || []);
            setHasMore(responseData?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "사용자 분석 결과 목록을 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }, [userId]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- userId가 바뀔 때마다 서버에서 해당 사용자의 결과 목록을 새로 불러와야 합니다.
        loadResults();
    }, [loadResults]);

    async function loadMoreResults() {
        try {
            setLoadingMore(true);
            setError("");

            const nextPage = page + 1;
            const response = await getAdminUserResults(userId, { page: nextPage });
            const responseData = response.data;

            setResults((prevResults) => [...prevResults, ...(responseData?.content || [])]);
            setPage(nextPage);
            setHasMore(responseData?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "추가 분석 결과를 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoadingMore(false);
        }
    }

    function getTotalScore(result) {
        const score = result?.scoreSummary?.totalScore;
        return typeof score === "number" ? score : null;
    }

    function formatScore(value) {
        return value === null || value === undefined ? "-" : value;
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

    async function handleDeleteResult(jobId) {
        const actionContext = await promptReason(
            `이 분석 결과(jobId: ${jobId})를 삭제합니다. 업로드 영상과 결과 파일도 함께 삭제되며 되돌릴 수 없습니다.`
        );
        if (!actionContext) {
            return;
        }

        try {
            setActionJobId(jobId);
            setError("");
            setSuccess("");

            await deleteAdminResult(jobId, actionContext);

            setResults((prevResults) => prevResults.filter((item) => item.jobId !== jobId));
            setSuccess(`분석 결과 ${jobId}를 삭제했습니다.`);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "분석 결과를 삭제하는 중 오류가 발생했습니다."
            ));
        } finally {
            setActionJobId("");
        }
    }

    return (
        <section className="page-section admin-user-detail-page">
            <header className="admin-page-hero admin-user-detail-hero">
                <div className="admin-page-hero-icon" aria-hidden="true">
                    <AdminUserDetailIcon name="records" />
                </div>
                <PageHeader
                    eyebrow="Admin account record"
                    title="사용자 분석 결과"
                    description={`사용자 #${userId}가 소유한 분석 작업과 결과 파일 상태를 확인합니다.`}
                />
                <div className="admin-page-hero-meta" aria-live="polite">
                    <span>Owner scope</span>
                    <strong>사용자 #{userId}</strong>
                    <p>{loading ? "결과를 불러오는 중입니다." : `${results.length}개 결과 표시${hasMore ? " · 추가 목록 있음" : ""}`}</p>
                </div>
            </header>

            <AdminNav />

            <div className="admin-detail-toolbar">
                <Link to="/admin/users" className="admin-back-link">
                    <AdminUserDetailIcon name="arrowBack" />
                    사용자 목록으로 돌아가기
                </Link>
                <p>
                    <AdminUserDetailIcon name="shield" />
                    이 화면에는 사용자 #{userId}의 소유 결과만 표시됩니다.
                </p>
            </div>

            <StateMessage type="error">{error}</StateMessage>
            <StateMessage type="success">{success}</StateMessage>

            <section className="admin-owned-results-section" aria-labelledby="admin-owned-results-title" aria-busy={loading || loadingMore}>
                <div className="admin-owned-results-heading">
                    <div>
                        <span>Owned analysis records</span>
                        <h2 id="admin-owned-results-title">소유 분석 결과</h2>
                        <p>표시된 job ID와 생성 경로를 확인한 후 관리 작업을 수행하세요.</p>
                    </div>
                    {!loading && <strong>{results.length}개 표시</strong>}
                </div>

                {loading ? (
                    <EmptyState
                        loading
                        title="사용자 분석 결과 로딩 중"
                        description="선택한 사용자의 소유 결과를 확인하고 있습니다."
                    />
                ) : error && results.length === 0 ? (
                    <div className="admin-user-state-panel">
                        <EmptyState
                            title="사용자 분석 결과를 표시할 수 없습니다."
                            description="오류 내용을 확인한 뒤 다시 시도해 주세요."
                        />
                        <button type="button" className="secondary-button" onClick={loadResults}>다시 시도</button>
                    </div>
                ) : results.length === 0 ? (
                    <EmptyState
                        title="표시할 분석 결과가 없습니다."
                        description="이 사용자는 아직 분석 결과가 없거나, 관리 작업으로 결과가 삭제되었습니다."
                    />
                ) : (
                    <div className="admin-owned-result-list">
                        {results.map((result, index) => {
                            const totalScore = getTotalScore(result);
                            const displayName = result.fileName || result.originalFileName || "분석 결과";

                            return (
                                <motion.article
                                className="admin-owned-result-card"
                                data-status={result.status || "UNKNOWN"}
                                key={result.jobId}
                                initial={prefersReducedMotion ? false : { opacity: 0, y: 12 }}
                                animate={{ opacity: 1, y: 0 }}
                                transition={{ duration: 0.34, delay: Math.min(index * 0.05, 0.2), ease: EASE_OUT }}
                            >
                                <header className="admin-owned-result-card-header">
                                    <div className="admin-owned-result-title">
                                        <span>Result {String(index + 1).padStart(2, "0")}</span>
                                        <h3 title={displayName}>{displayName}</h3>
                                        <p>job ID <code>{result.jobId}</code></p>
                                    </div>

                                    <StatusBadge
                                        status={result.status}
                                        label={result.statusDescription || result.status}
                                    />
                                </header>

                                {result.dataIssue && (
                                    <p className="admin-result-data-issue" role="alert">
                                        <AdminUserDetailIcon name="warning" />
                                        <span>{result.dataIssueDescription || "이 결과의 일부 데이터에 문제가 있습니다."}</span>
                                    </p>
                                )}

                                <div className="admin-owned-result-summary">
                                    <div>
                                        <span>총점</span>
                                        <strong>{formatScore(totalScore)}</strong>
                                        <small>100점 기준</small>
                                    </div>

                                    <div>
                                        <span>등급</span>
                                        <strong>{result.scoreSummary?.level || "-"}</strong>
                                        <small>분석 결과 등급</small>
                                    </div>

                                    <div>
                                        <span>생성일</span>
                                        <strong>{formatDateTime(result.createdAt)}</strong>
                                        <small>서버 기록 시각</small>
                                    </div>
                                </div>

                                <section className="admin-generation-provenance" aria-label={`${displayName} 생성 경로`}>
                                    <div className="admin-generation-provenance-heading">
                                        <span>Generation provenance</span>
                                        <strong>AI 생성 경로</strong>
                                    </div>
                                    <div className="admin-generation-provenance-grid">
                                        <OpenAiGenerationBadge
                                            feedback={result.feedback}
                                            pipeline={result.pipeline}
                                        />
                                        <VideoLlmGenerationBadge result={result} />
                                    </div>
                                </section>

                                <footer className="admin-result-danger-zone">
                                    <p>
                                        <AdminUserDetailIcon name="info" />
                                        삭제하면 업로드 영상과 결과 파일이 함께 제거되며 되돌릴 수 없습니다.
                                    </p>
                                    <button
                                        type="button"
                                        className="danger-button"
                                        onClick={() => handleDeleteResult(result.jobId)}
                                        disabled={actionJobId === result.jobId}
                                    >
                                        <AdminUserDetailIcon name="trash" />
                                        {actionJobId === result.jobId ? "삭제 중..." : "결과 삭제"}
                                    </button>
                                </footer>
                                </motion.article>
                            );
                        })}
                    </div>
                )}

                {hasMore && !loading && (
                    <div className="admin-owned-results-load-more">
                        <span>현재 {results.length}개 결과를 표시하고 있습니다.</span>
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
        </section>
    );
}

function AdminUserDetailIcon({ name }) {
    const paths = {
        records: "M5 3h14v18H5V3Zm2 2v14h10V5H7Zm2 2h6v2H9V7Zm0 4h6v2H9v-2Zm0 4h4v2H9v-2Z",
        arrowBack: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.42-1.41L7.83 13H20v-2Z",
        shield: "M12 2 4 5v6c0 5.05 3.41 9.74 8 11 4.59-1.26 8-5.95 8-11V5l-8-3Zm-1 14-3-3 1.4-1.4 1.6 1.59 3.6-3.59L16 11l-5 5Z",
        warning: "M1 21h22L12 2 1 21Zm12-3h-2v-2h2v2Zm0-4h-2v-4h2v4Z",
        info: "M11 10h2v7h-2v-7Zm0-3h2v2h-2V7Zm1-5a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16Z",
        trash: "M7 21a2 2 0 0 1-2-2V7h14v12a2 2 0 0 1-2 2H7ZM9 3h6l1 2h4v2H4V5h4l1-2Zm0 7v7h2v-7H9Zm4 0v7h2v-7h-2Z",
    };

    return (
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path fill="currentColor" d={paths[name]} />
        </svg>
    );
}

export default AdminUserDetailPage;
