import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { deleteAdminResult, getAdminUserResults } from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import EmptyState from "../components/EmptyState";
import OpenAiGenerationBadge from "../components/result-detail/OpenAiGenerationBadge";
import VideoLlmGenerationBadge from "../components/result-detail/VideoLlmGenerationBadge";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import { useConfirm } from "../context/ConfirmContext";

function AdminUserDetailPage() {
    const { userId } = useParams();
    const confirm = useConfirm();
    const [actionJobId, setActionJobId] = useState("");
    const [results, setResults] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState("");

    const loadResults = useCallback(async () => {
        try {
            setLoading(true);
            setError("");
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
        return typeof score === "number" ? score : 0;
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
        const confirmed = await confirm(
            "이 분석 결과를 삭제하시겠습니까? 업로드 영상과 결과 파일도 함께 삭제되며 되돌릴 수 없습니다."
        );
        if (!confirmed) {
            return;
        }

        try {
            setActionJobId(jobId);
            setError("");

            await deleteAdminResult(jobId);

            setResults((prevResults) => prevResults.filter((item) => item.jobId !== jobId));
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "분석 결과를 삭제하는 중 오류가 발생했습니다."
            ));
        } finally {
            setActionJobId("");
        }
    }

    if (loading) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Admin"
                    title="사용자 분석 결과"
                    description="선택한 사용자의 분석 결과를 불러오는 중입니다."
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
                eyebrow="Admin"
                title="사용자 분석 결과"
                description={`사용자 #${userId}의 분석 작업과 결과를 확인합니다.`}
            />

            <p className="policy-links">
                <Link to="/admin">← 관리자 대시보드로 돌아가기</Link>
            </p>

            <StateMessage type="error">{error}</StateMessage>

            {results.length === 0 ? (
                <EmptyState
                    title="표시할 분석 결과가 없습니다."
                    description="이 사용자는 아직 분석 결과가 없습니다."
                />
            ) : (
                <div className="result-list">
                    {results.map((result) => {
                        const totalScore = getTotalScore(result);

                        return (
                            <article className="result-card" key={result.jobId}>
                                <div className="result-card-header">
                                    <div>
                                        <h3>{result.fileName || result.originalFileName || "분석 결과"}</h3>
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

                                <OpenAiGenerationBadge feedback={result.feedback} />
                                <VideoLlmGenerationBadge result={result} />

                                <div className="result-score-row">
                                    <div>
                                        <span>총점</span>
                                        <strong>{formatScore(totalScore)}</strong>
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

                                <div className="button-row">
                                    <button
                                        type="button"
                                        className="danger-button"
                                        onClick={() => handleDeleteResult(result.jobId)}
                                        disabled={actionJobId === result.jobId}
                                    >
                                        {actionJobId === result.jobId ? "삭제 중..." : "삭제"}
                                    </button>
                                </div>
                            </article>
                        );
                    })}
                </div>
            )}

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

export default AdminUserDetailPage;
