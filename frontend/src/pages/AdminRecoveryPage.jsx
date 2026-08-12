import { useCallback, useEffect, useState } from "react";
import {
    getAdminDeadLetterJobs,
    getAdminPasswordResetEmailDeadLetters,
    getAdminStorageDeletionDeadLetters,
    requeueAdminDeadLetterJob,
    requeueAdminPasswordResetEmailDeadLetter,
    requeueAdminStorageDeletionDeadLetter,
} from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import AdminNav from "../components/admin/AdminNav";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { useReasonPrompt } from "../context/ConfirmContext";

const ANALYSIS_COLUMNS = [
    { key: "jobId", label: "작업 ID", render: (item) => item.jobId },
    { key: "ownerId", label: "사용자 ID", render: (item) => item.ownerId ?? "-" },
    { key: "failReason", label: "실패 사유", render: (item) => item.failReason || "-" },
    { key: "retryCount", label: "재시도", render: (item) => item.retryCount },
    { key: "completedAt", label: "마지막 실패", render: (item) => formatDateTime(item.completedAt) },
];

const STORAGE_COLUMNS = [
    { key: "id", label: "작업 ID", render: (item) => item.id },
    { key: "jobId", label: "분석 ID", render: (item) => item.jobId || "-" },
    { key: "reason", label: "삭제 사유", render: (item) => item.reason || "-" },
    { key: "attemptCount", label: "시도 횟수", render: (item) => item.attemptCount },
    { key: "lastError", label: "마지막 오류", render: (item) => item.lastError || "-" },
    { key: "createdAt", label: "생성 시각", render: (item) => formatDateTime(item.createdAt) },
];

const PASSWORD_RESET_COLUMNS = [
    { key: "id", label: "작업 ID", render: (item) => item.id },
    { key: "userId", label: "사용자 ID", render: (item) => item.userId ?? "-" },
    { key: "maskedRecipientEmail", label: "수신자", render: (item) => item.maskedRecipientEmail || "-" },
    { key: "attemptCount", label: "시도 횟수", render: (item) => item.attemptCount },
    { key: "lastError", label: "마지막 오류", render: (item) => item.lastError || "-" },
    { key: "tokenExpiresAt", label: "토큰 만료", render: (item) => formatDateTime(item.tokenExpiresAt) },
];

function AdminRecoveryPage() {
    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Admin"
                title="복구 작업"
                description="자동 재시도를 모두 소진해 관리자 판단이 필요한 업무 작업만 관리합니다. 시스템 지표와 장애 추세는 운영 모니터링에서 확인합니다."
            />
            <AdminNav />

            <div className="grid gap-8">
                <RecoveryQueueSection
                    sectionId="analysis-recovery"
                    title="분석 작업"
                    description="분석 엔진 또는 외부 연동 실패로 재시도를 모두 소진한 작업입니다."
                    loadItems={getAdminDeadLetterJobs}
                    requeueItem={(item, actionContext) => requeueAdminDeadLetterJob(item.jobId, actionContext)}
                    itemKey={(item) => item.jobId}
                    columns={ANALYSIS_COLUMNS}
                    emptyTitle="재시도 소진 분석 작업이 없습니다."
                    confirmMessage={(item) => `분석 작업 ${item.jobId}을(를) 다시 큐에 넣으시겠습니까?`}
                />

                <RecoveryQueueSection
                    sectionId="storage-recovery"
                    title="스토리지 삭제 작업"
                    description="원본 영상이나 결과 파일 삭제를 완료하지 못한 작업입니다. 스토리지 상태를 확인한 뒤 재큐잉하세요."
                    loadItems={getAdminStorageDeletionDeadLetters}
                    requeueItem={(item, actionContext) => requeueAdminStorageDeletionDeadLetter(item.id, actionContext)}
                    itemKey={(item) => item.id}
                    columns={STORAGE_COLUMNS}
                    emptyTitle="재시도 소진 스토리지 삭제 작업이 없습니다."
                    confirmMessage={(item) => `스토리지 삭제 작업 #${item.id}을(를) 다시 큐에 넣으시겠습니까?`}
                />

                <RecoveryQueueSection
                    sectionId="password-reset-recovery"
                    title="비밀번호 재설정 이메일"
                    description="이메일 발송에 반복 실패한 작업입니다. 토큰 만료 여부와 메일 발송 상태를 확인한 뒤 재큐잉하세요."
                    loadItems={getAdminPasswordResetEmailDeadLetters}
                    requeueItem={(item, actionContext) => requeueAdminPasswordResetEmailDeadLetter(item.id, actionContext)}
                    itemKey={(item) => item.id}
                    columns={PASSWORD_RESET_COLUMNS}
                    emptyTitle="재시도 소진 비밀번호 재설정 이메일이 없습니다."
                    confirmMessage={(item) => `비밀번호 재설정 이메일 작업 #${item.id}을(를) 다시 큐에 넣으시겠습니까?`}
                />
            </div>
        </section>
    );
}

function RecoveryQueueSection({
    sectionId,
    title,
    description,
    loadItems,
    requeueItem,
    itemKey,
    columns,
    emptyTitle,
    confirmMessage,
}) {
    const promptReason = useReasonPrompt();
    const [items, setItems] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [actionId, setActionId] = useState(null);
    const [error, setError] = useState("");

    const loadInitial = useCallback(async () => {
        try {
            setLoading(true);
            setError("");
            const response = await loadItems({ page: 0 });
            setItems(response.data?.content || []);
            setPage(0);
            setHasMore(response.data?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(requestError, `${title} 목록을 불러오는 중 오류가 발생했습니다.`));
        } finally {
            setLoading(false);
        }
    }, [loadItems, title]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 각 복구 큐를 독립적으로 불러와 부분 실패를 격리합니다.
        loadInitial();
    }, [loadInitial]);

    async function loadMore() {
        try {
            setLoadingMore(true);
            setError("");
            const nextPage = page + 1;
            const response = await loadItems({ page: nextPage });
            setItems((previous) => [...previous, ...(response.data?.content || [])]);
            setPage(nextPage);
            setHasMore(response.data?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(requestError, `${title} 목록을 더 불러오는 중 오류가 발생했습니다.`));
        } finally {
            setLoadingMore(false);
        }
    }

    async function handleRequeue(item) {
        const actionContext = await promptReason(confirmMessage(item));
        if (!actionContext) {
            return;
        }

        const id = itemKey(item);
        try {
            setActionId(id);
            setError("");
            await requeueItem(item, actionContext);
            setItems((previous) => previous.filter((current) => itemKey(current) !== id));
        } catch (requestError) {
            setError(getErrorMessage(requestError, `${title}을(를) 다시 큐에 넣는 중 오류가 발생했습니다.`));
        } finally {
            setActionId(null);
        }
    }

    return (
        <section className="result-card" aria-labelledby={`${sectionId}-heading`}>
            <div className="result-card-header">
                <div>
                    <h3 id={`${sectionId}-heading`}>{title}</h3>
                    <p>{description}</p>
                </div>
                <button type="button" className="secondary-button" onClick={loadInitial} disabled={loading}>
                    {loading ? "확인 중..." : "새로고침"}
                </button>
            </div>

            <StateMessage type="error">{error}</StateMessage>

            {loading ? (
                <EmptyState loading title={`${title} 로딩 중`} description="잠시만 기다려 주세요." />
            ) : items.length === 0 ? (
                <EmptyState title={emptyTitle} description="현재 관리자가 처리할 항목이 없습니다." />
            ) : (
                <div className="pose-frame-table-wrap overflow-x-auto">
                    <table className="pose-frame-table">
                        <thead>
                            <tr>
                                {columns.map((column) => <th key={column.key}>{column.label}</th>)}
                                <th>관리</th>
                            </tr>
                        </thead>
                        <tbody>
                            {items.map((item) => {
                                const id = itemKey(item);
                                return (
                                    <tr key={id}>
                                        {columns.map((column) => (
                                            <td key={column.key}>{column.render(item)}</td>
                                        ))}
                                        <td>
                                            <button
                                                type="button"
                                                className="secondary-button"
                                                onClick={() => handleRequeue(item)}
                                                disabled={actionId === id}
                                            >
                                                {actionId === id ? "처리 중..." : "다시 큐에 넣기"}
                                            </button>
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            )}

            {hasMore && (
                <div className="button-row">
                    <button type="button" className="secondary-button" onClick={loadMore} disabled={loadingMore}>
                        {loadingMore ? "불러오는 중..." : "더 보기"}
                    </button>
                </div>
            )}
        </section>
    );
}

function formatDateTime(value) {
    if (!value) {
        return "-";
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? value
        : date.toLocaleString("ko-KR", {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
        });
}

export default AdminRecoveryPage;
