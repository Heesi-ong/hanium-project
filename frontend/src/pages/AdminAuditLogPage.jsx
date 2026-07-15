import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getAdminAuditLogs } from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";

const ACTION_LABELS = {
    SUSPEND_USER: "계정 정지",
    ACTIVATE_USER: "계정 활성화",
    FORCE_WITHDRAW_USER: "강제 탈퇴",
    DELETE_RESULT: "결과 삭제",
};

const TARGET_TYPE_LABELS = {
    USER: "사용자",
    ANALYSIS_JOB: "분석 작업",
};

function AdminAuditLogPage() {
    const [logs, setLogs] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState("");

    const loadLogs = useCallback(async () => {
        try {
            const response = await getAdminAuditLogs({ page: 0 });
            const responseData = response.data;

            setLogs(responseData?.content || []);
            setHasMore(responseData?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "감사로그를 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 마운트 시 감사로그를 불러와 화면에 반영해야 합니다.
        loadLogs();
    }, [loadLogs]);

    async function loadMoreLogs() {
        try {
            setLoadingMore(true);
            setError("");

            const nextPage = page + 1;
            const response = await getAdminAuditLogs({ page: nextPage });
            const responseData = response.data;

            setLogs((prevLogs) => [...prevLogs, ...(responseData?.content || [])]);
            setPage(nextPage);
            setHasMore(responseData?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "감사로그를 더 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoadingMore(false);
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

    function formatAction(action) {
        return ACTION_LABELS[action] || action;
    }

    function formatTargetType(targetType) {
        return TARGET_TYPE_LABELS[targetType] || targetType;
    }

    if (loading) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Admin"
                    title="관리자 감사로그"
                    description="관리자 조치 이력을 불러오는 중입니다."
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
                title="관리자 감사로그"
                description="관리자가 수행한 정지/활성화/강제탈퇴/결과삭제 이력을 확인합니다."
            />

            <p className="policy-links">
                <Link to="/admin">← 관리자 대시보드로 돌아가기</Link>
            </p>

            <StateMessage type="error">{error}</StateMessage>

            {logs.length === 0 ? (
                <EmptyState
                    title="표시할 감사로그가 없습니다."
                    description="아직 관리자 조치 이력이 없습니다."
                />
            ) : (
                <div className="pose-frame-table-wrap">
                    <h3>조치 이력</h3>

                    <table className="pose-frame-table">
                        <thead>
                            <tr>
                                <th>시각</th>
                                <th>관리자</th>
                                <th>작업</th>
                                <th>대상 유형</th>
                                <th>대상 ID</th>
                                <th>상세</th>
                            </tr>
                        </thead>
                        <tbody>
                            {logs.map((log) => (
                                <tr key={log.id}>
                                    <td>{formatDateTime(log.createdAt)}</td>
                                    <td>{log.adminEmail}</td>
                                    <td>{formatAction(log.action)}</td>
                                    <td>{formatTargetType(log.targetType)}</td>
                                    <td>{log.targetId}</td>
                                    <td>{log.detail || "-"}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {hasMore && (
                <div className="button-row">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={loadMoreLogs}
                        disabled={loadingMore}
                    >
                        {loadingMore ? "불러오는 중..." : "더 보기"}
                    </button>
                </div>
            )}
        </section>
    );
}

export default AdminAuditLogPage;
