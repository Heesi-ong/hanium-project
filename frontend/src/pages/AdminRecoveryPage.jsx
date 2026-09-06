import { useCallback, useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
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
import { EASE_OUT } from "../components/motion/animationVariants";
import { useReasonPrompt } from "../context/ConfirmContext";

const ANALYSIS_COLUMNS = [
    { key: "ownerId", label: "사용자 ID", render: (item) => item.ownerId ?? "-" },
    { key: "failReason", label: "실패 사유", render: (item) => item.failReason || "-" },
    { key: "retryCount", label: "재시도 횟수", render: (item) => item.retryCount },
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
    { key: "maskedRecipientEmail", label: "마스킹된 수신자", render: (item) => item.maskedRecipientEmail || "-" },
    { key: "attemptCount", label: "시도 횟수", render: (item) => item.attemptCount },
    { key: "lastError", label: "마지막 오류", render: (item) => item.lastError || "-" },
    { key: "tokenExpiresAt", label: "토큰 만료", render: (item) => formatDateTime(item.tokenExpiresAt) },
];

const RECOVERY_STEPS = [
    { number: "01", title: "원인 확인", description: "작업 ID로 로컬 로그와 연동 상태를 확인합니다." },
    { number: "02", title: "사유 기록", description: "관리 조치 사유와 선택적인 인시던트 번호를 남깁니다." },
    { number: "03", title: "재큐잉", description: "확인한 항목만 다시 처리 대기열로 보냅니다." },
    { number: "04", title: "상태 재확인", description: "접수 후 실제 처리 상태는 각 작업 흐름에서 다시 확인합니다." },
];

function AdminRecoveryPage() {
    return (
        <section className="page-section admin-recovery-page">
            <header className="admin-page-hero admin-recovery-hero">
                <div className="admin-page-hero-icon" aria-hidden="true">
                    <RecoveryIcon name="recovery" />
                </div>
                <PageHeader
                    eyebrow="Admin recovery desk"
                    title="복구 작업"
                    description="자동 재시도를 모두 소진한 작업의 원인을 확인하고, 관리 사유를 남긴 뒤 안전하게 재큐잉합니다."
                />
                <div className="admin-page-hero-meta">
                    <span>Recovery scope</span>
                    <strong>3개 대기열</strong>
                    <p>각 큐는 독립적으로 조회·처리됩니다.</p>
                </div>
            </header>

            <AdminNav />

            <section className="admin-recovery-protocol" aria-labelledby="admin-recovery-protocol-title">
                <div className="admin-recovery-protocol-heading">
                    <span>Safe recovery protocol</span>
                    <h2 id="admin-recovery-protocol-title">재큐잉 전 확인 순서</h2>
                    <p>재큐잉 접수는 작업 완료를 의미하지 않습니다. 원인이 해소되었는지 먼저 확인하세요.</p>
                </div>
                <ol className="admin-recovery-steps">
                    {RECOVERY_STEPS.map((step) => (
                        <li key={step.number}>
                            <span>{step.number}</span>
                            <div>
                                <strong>{step.title}</strong>
                                <p>{step.description}</p>
                            </div>
                        </li>
                    ))}
                </ol>
                <p className="admin-recovery-audit-note">
                    <RecoveryIcon name="shield" />
                    <span>모든 수동 재큐잉은 사유 입력이 필요하며 관리자 감사 기록에 남습니다.</span>
                </p>
            </section>

            <div className="admin-recovery-queue-list">
                <RecoveryQueueSection
                    sectionId="analysis-recovery"
                    sequence="01"
                    icon="analysis"
                    title="분석 작업"
                    description="분석 엔진 또는 외부 연동 실패로 재시도를 모두 소진한 작업입니다."
                    guidance="재큐잉 전 jobId의 분석 로그와 엔진 가용성을 확인하세요."
                    loadItems={getAdminDeadLetterJobs}
                    requeueItem={(item, actionContext) => requeueAdminDeadLetterJob(item.jobId, actionContext)}
                    itemKey={(item) => item.jobId}
                    itemTitle={(item) => item.jobId || "작업 ID 없음"}
                    itemEyebrow="Analysis job"
                    columns={ANALYSIS_COLUMNS}
                    emptyTitle="재시도 소진 분석 작업이 없습니다."
                    confirmMessage={(item) => `분석 작업 ${item.jobId}을(를) 다시 큐에 넣으시겠습니까?`}
                />

                <RecoveryQueueSection
                    sectionId="storage-recovery"
                    sequence="02"
                    icon="storage"
                    title="스토리지 삭제 작업"
                    description="원본 영상이나 결과 파일 삭제를 완료하지 못한 작업입니다."
                    guidance="대상 파일과 스토리지 연결 상태를 확인한 뒤 재큐잉하세요."
                    loadItems={getAdminStorageDeletionDeadLetters}
                    requeueItem={(item, actionContext) => requeueAdminStorageDeletionDeadLetter(item.id, actionContext)}
                    itemKey={(item) => item.id}
                    itemTitle={(item) => `삭제 작업 #${item.id ?? "-"}`}
                    itemEyebrow="Storage deletion"
                    columns={STORAGE_COLUMNS}
                    emptyTitle="재시도 소진 스토리지 삭제 작업이 없습니다."
                    confirmMessage={(item) => `스토리지 삭제 작업 #${item.id}을(를) 다시 큐에 넣으시겠습니까?`}
                />

                <RecoveryQueueSection
                    sectionId="password-reset-recovery"
                    sequence="03"
                    icon="email"
                    title="비밀번호 재설정 이메일"
                    description="이메일 발송에 반복 실패한 작업입니다."
                    guidance="토큰 만료 여부와 메일 발송 상태를 확인한 뒤 재큐잉하세요."
                    loadItems={getAdminPasswordResetEmailDeadLetters}
                    requeueItem={(item, actionContext) => requeueAdminPasswordResetEmailDeadLetter(item.id, actionContext)}
                    itemKey={(item) => item.id}
                    itemTitle={(item) => `이메일 작업 #${item.id ?? "-"}`}
                    itemEyebrow="Password reset email"
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
    sequence,
    icon,
    title,
    description,
    guidance,
    loadItems,
    requeueItem,
    itemKey,
    itemTitle,
    itemEyebrow,
    columns,
    emptyTitle,
    confirmMessage,
}) {
    const promptReason = useReasonPrompt();
    const prefersReducedMotion = useReducedMotion();
    const [items, setItems] = useState([]);
    const [totalElements, setTotalElements] = useState(null);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [actionId, setActionId] = useState(null);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    const loadInitial = useCallback(async () => {
        try {
            setLoading(true);
            setError("");
            setSuccess("");
            const response = await loadItems({ page: 0 });
            const content = response.data?.content || [];
            setItems(content);
            setTotalElements(response.data?.totalElements ?? content.length);
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
            setSuccess("");
            const nextPage = page + 1;
            const response = await loadItems({ page: nextPage });
            const content = response.data?.content || [];
            setItems((previous) => [...previous, ...content]);
            setTotalElements(response.data?.totalElements ?? null);
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
            setSuccess("");
            await requeueItem(item, actionContext);
            setItems((previous) => previous.filter((current) => itemKey(current) !== id));
            setTotalElements((previous) => typeof previous === "number" ? Math.max(0, previous - 1) : previous);
            setSuccess(`${title} 항목의 재큐잉을 접수했습니다. 실제 처리 상태를 다시 확인하세요.`);
        } catch (requestError) {
            setError(getErrorMessage(requestError, `${title}을(를) 다시 큐에 넣는 중 오류가 발생했습니다.`));
        } finally {
            setActionId(null);
        }
    }

    const queueCount = loading && items.length === 0
        ? "확인 중"
        : `${totalElements ?? items.length}건 대기`;
    const sectionMotion = prefersReducedMotion
        ? { initial: false }
        : {
            initial: { opacity: 0, y: 16 },
            whileInView: { opacity: 1, y: 0 },
            viewport: { once: true, amount: 0.1 },
            transition: { duration: 0.42, delay: Number(sequence) * 0.05, ease: EASE_OUT },
        };

    return (
        <motion.section
            className="admin-recovery-queue-section"
            aria-labelledby={`${sectionId}-heading`}
            aria-busy={loading || loadingMore}
            {...sectionMotion}
        >
            <div className="admin-recovery-queue-heading">
                <div className="admin-recovery-queue-identity">
                    <span className="admin-recovery-queue-sequence">{sequence}</span>
                    <span className="admin-recovery-queue-icon" aria-hidden="true">
                        <RecoveryIcon name={icon} />
                    </span>
                    <div>
                        <span>Dead letter queue</span>
                        <h2 id={`${sectionId}-heading`}>{title}</h2>
                        <p>{description}</p>
                    </div>
                </div>
                <div className="admin-recovery-queue-controls">
                    <strong>{queueCount}</strong>
                    <button type="button" className="secondary-button" onClick={loadInitial} disabled={loading}>
                        <RecoveryIcon name="refresh" />
                        {loading ? "확인 중..." : "새로고침"}
                    </button>
                </div>
            </div>

            <p className="admin-recovery-guidance">
                <RecoveryIcon name="check" />
                <span>{guidance}</span>
            </p>

            <StateMessage type="error">{error}</StateMessage>
            <StateMessage type="success">{success}</StateMessage>

            {loading && items.length === 0 ? (
                <EmptyState loading title={`${title} 로딩 중`} description="이 대기열의 서버 상태를 확인하고 있습니다." />
            ) : error && items.length === 0 ? (
                <div className="admin-recovery-state-panel">
                    <EmptyState title={`${title}을(를) 표시할 수 없습니다.`} description="오류 내용을 확인한 뒤 이 대기열만 다시 시도해 주세요." />
                    <button type="button" className="secondary-button" onClick={loadInitial}>다시 시도</button>
                </div>
            ) : items.length === 0 ? (
                <EmptyState title={emptyTitle} description="현재 관리자가 처리할 항목이 없습니다." />
            ) : (
                <div className="admin-recovery-task-list" role="list">
                    {items.map((item, index) => {
                        const id = itemKey(item);
                        return (
                            <motion.article
                                key={id}
                                className="admin-recovery-task-card"
                                role="listitem"
                                initial={prefersReducedMotion ? false : { opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                transition={{ duration: 0.32, delay: prefersReducedMotion ? 0 : index * 0.04, ease: EASE_OUT }}
                            >
                                <div className="admin-recovery-task-header">
                                    <div>
                                        <span>{itemEyebrow}</span>
                                        <h3>{itemTitle(item)}</h3>
                                    </div>
                                    <strong className="admin-recovery-dead-letter-badge">
                                        <i aria-hidden="true" />
                                        재시도 소진
                                    </strong>
                                </div>

                                <dl className="admin-recovery-task-details">
                                    {columns.map((column) => (
                                        <div key={column.key}>
                                            <dt>{column.label}</dt>
                                            <dd>{column.render(item)}</dd>
                                        </div>
                                    ))}
                                </dl>

                                <div className="admin-recovery-task-action">
                                    <p>
                                        <RecoveryIcon name="shield" />
                                        <span>원인 해소를 확인하고 관리 사유를 기록한 경우에만 실행하세요.</span>
                                    </p>
                                    <button
                                        type="button"
                                        className="secondary-button admin-requeue-button"
                                        onClick={() => handleRequeue(item)}
                                        disabled={actionId === id}
                                    >
                                        <RecoveryIcon name="queue" />
                                        {actionId === id ? "처리 중..." : "다시 큐에 넣기"}
                                    </button>
                                </div>
                            </motion.article>
                        );
                    })}
                </div>
            )}

            {hasMore && (
                <div className="admin-recovery-load-more">
                    <span>현재 {items.length}개 항목을 표시하고 있습니다.</span>
                    <button type="button" className="secondary-button" onClick={loadMore} disabled={loadingMore}>
                        {loadingMore ? "불러오는 중..." : "더 보기"}
                    </button>
                </div>
            )}
        </motion.section>
    );
}

function RecoveryIcon({ name }) {
    const paths = {
        recovery: "M12 3a9 9 0 0 0-8.52 6H1l3.2 3.2L7.4 9H5.6A7 7 0 1 1 6 16.45l-1.43 1.4A9 9 0 1 0 12 3Zm-1 5v5l4.25 2.52.75-1.23-3.5-2.04V8H11Z",
        analysis: "M5 3h14v4H5V3Zm0 7h14v4H5v-4Zm0 7h9v4H5v-4Zm11.3.3 1.2-1.2 3.4 3.4-3.4 3.4-1.2-1.2 2.2-2.2-2.2-2.2Z",
        storage: "M12 3C7.58 3 4 4.34 4 6s3.58 3 8 3 8-1.34 8-3-3.58-3-8-3ZM4 9.5V13c0 1.66 3.58 3 8 3s8-1.34 8-3V9.5c-1.72 1.02-4.62 1.5-8 1.5S5.72 10.52 4 9.5Zm0 7V18c0 1.66 3.58 3 8 3s8-1.34 8-3v-1.5c-1.72 1.02-4.62 1.5-8 1.5s-6.28-.48-8-1.5Z",
        email: "M3 5h18v14H3V5Zm2 2v.3l7 4.9 7-4.9V7H5Zm14 10V9.75l-7 4.9-7-4.9V17h14Z",
        refresh: "M12 4a8 8 0 0 0-7.45 5H2l3.4 3.4L8.8 9H6.7A6 6 0 1 1 7 15.35l-1.45 1.38A8 8 0 1 0 12 4Z",
        queue: "M4 4h12v2H4V4Zm0 7h10v2H4v-2Zm0 7h8v2H4v-2Zm12-3 5 4-5 4v-3h-2v-2h2v-3Z",
        shield: "M12 2 20 5v6c0 5.1-3.4 9.44-8 11-4.6-1.56-8-5.9-8-11V5l8-3Zm0 3.05L7 6.92V11c0 3.5 2.13 6.64 5 7.9 2.87-1.26 5-4.4 5-7.9V6.92l-5-1.87Zm-1 3h2v5h-2v-5Zm0 7h2v2h-2v-2Z",
        check: "m9.2 16.6-4.1-4.1 1.4-1.4 2.7 2.7 8.3-8.3 1.4 1.4-9.7 9.7Z",
    };

    return (
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path fill="currentColor" d={paths[name]} />
        </svg>
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
