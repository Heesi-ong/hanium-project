import { useCallback, useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import { getAdminAuditLogs } from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import AdminNav from "../components/admin/AdminNav";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { EASE_OUT } from "../components/motion/animationVariants";

const ACTION_LABELS = {
    SUSPEND_USER: "계정 정지",
    ACTIVATE_USER: "계정 활성화",
    FORCE_WITHDRAW_USER: "강제 탈퇴",
    DELETE_RESULT: "결과 삭제",
    REQUEUE_DEAD_LETTER_JOB: "분석 작업 재큐잉",
    REQUEUE_STORAGE_DELETION_TASK: "스토리지 삭제 재큐잉",
    REQUEUE_PASSWORD_RESET_EMAIL_TASK: "비밀번호 이메일 재큐잉",
};

const TARGET_TYPE_LABELS = {
    USER: "사용자",
    ANALYSIS_JOB: "분석 작업",
    STORAGE_DELETION_TASK: "스토리지 삭제 작업",
    PASSWORD_RESET_EMAIL_TASK: "비밀번호 재설정 이메일",
};

function AdminAuditLogPage() {
    const prefersReducedMotion = useReducedMotion();
    const [logs, setLogs] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState("");
    const [adminEmail, setAdminEmail] = useState("");
    const [actionFilter, setActionFilter] = useState("");
    const [targetTypeFilter, setTargetTypeFilter] = useState("");
    const [targetId, setTargetId] = useState("");
    const [fromDateTime, setFromDateTime] = useState("");
    const [toDateTime, setToDateTime] = useState("");
    const [appliedFilters, setAppliedFilters] = useState({});
    const activeFilters = getActiveFilters(appliedFilters);
    const hasAppliedFilters = activeFilters.length > 0;

    const loadLogs = useCallback(async () => {
        try {
            setLoading(true);
            setError("");
            const response = await getAdminAuditLogs({ page: 0, ...appliedFilters });
            const responseData = response.data;

            setLogs(responseData?.content || []);
            setPage(0);
            setHasMore(responseData?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "감사로그를 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }, [appliedFilters]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 마운트 시 감사로그를 불러와 화면에 반영해야 합니다.
        loadLogs();
    }, [loadLogs]);

    async function loadMoreLogs() {
        try {
            setLoadingMore(true);
            setError("");

            const nextPage = page + 1;
            const response = await getAdminAuditLogs({ page: nextPage, ...appliedFilters });
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

    function formatAction(action) {
        return ACTION_LABELS[action] || action;
    }

    function formatTargetType(targetType) {
        return TARGET_TYPE_LABELS[targetType] || targetType;
    }

    function handleFilterSubmit(event) {
        event.preventDefault();
        setAppliedFilters({
            adminEmail: adminEmail.trim() || undefined,
            action: actionFilter || undefined,
            targetType: targetTypeFilter || undefined,
            targetId: targetId.trim() || undefined,
            from: fromDateTime || undefined,
            to: toDateTime || undefined,
        });
    }

    function clearFilters() {
        setAdminEmail("");
        setActionFilter("");
        setTargetTypeFilter("");
        setTargetId("");
        setFromDateTime("");
        setToDateTime("");
        setAppliedFilters({});
    }

    return (
        <section className="page-section admin-audit-page">
            <header className="admin-page-hero admin-audit-hero">
                <div className="admin-page-hero-icon" aria-hidden="true">
                    <AdminAuditIcon name="log" />
                </div>
                <PageHeader
                    eyebrow="Admin audit trail"
                    title="관리자 감사로그"
                    description="관리자가 수행한 정지·활성화·강제탈퇴·결과삭제·재큐잉 이력을 시각·행위자·대상별로 확인합니다."
                />
                <div className="admin-page-hero-meta" aria-live="polite">
                    <span>현재 목록</span>
                    <strong>{loading && logs.length === 0 ? "불러오는 중" : `${logs.length}건 표시`}</strong>
                    <p>{hasMore ? "추가 조치 이력이 있습니다." : "불러온 이력의 마지막입니다."}</p>
                </div>
            </header>

            <AdminNav />

            <section className="admin-audit-workspace" aria-labelledby="admin-audit-filter-title">
                <div className="admin-user-workspace-heading">
                    <div>
                        <span>Search & filter</span>
                        <h2 id="admin-audit-filter-title">조치 이력 검색</h2>
                        <p>관리자, 작업 종류, 대상, 기간을 조합해 서버 로그를 조회합니다.</p>
                    </div>
                    <button type="button" className="secondary-button admin-list-refresh" onClick={loadLogs} disabled={loading}>
                        <AdminAuditIcon name="refresh" />
                        {loading ? "새로고침 중..." : "목록 새로고침"}
                    </button>
                </div>

                <form className="admin-user-filter-form" onSubmit={handleFilterSubmit}>
                    <label className="admin-filter-field is-search">
                        <span>관리자 이메일</span>
                        <div className="admin-filter-input-wrap">
                            <AdminAuditIcon name="search" />
                            <input
                                type="search"
                                value={adminEmail}
                                onChange={(event) => setAdminEmail(event.target.value)}
                                placeholder="이메일 일부 입력"
                            />
                        </div>
                    </label>
                    <label className="admin-filter-field">
                        <span>작업</span>
                        <select value={actionFilter} onChange={(event) => setActionFilter(event.target.value)}>
                            <option value="">전체 작업</option>
                            {Object.entries(ACTION_LABELS).map(([value, label]) => (
                                <option key={value} value={value}>{label}</option>
                            ))}
                        </select>
                    </label>
                    <label className="admin-filter-field">
                        <span>대상 유형</span>
                        <select value={targetTypeFilter} onChange={(event) => setTargetTypeFilter(event.target.value)}>
                            <option value="">전체 대상</option>
                            {Object.entries(TARGET_TYPE_LABELS).map(([value, label]) => (
                                <option key={value} value={value}>{label}</option>
                            ))}
                        </select>
                    </label>
                    <label className="admin-filter-field">
                        <span>대상 ID</span>
                        <input
                            type="search"
                            value={targetId}
                            onChange={(event) => setTargetId(event.target.value)}
                            placeholder="대상 ID 일부 입력"
                        />
                    </label>
                    <label className="admin-filter-field">
                        <span>시작 시각</span>
                        <input type="datetime-local" value={fromDateTime} onChange={(event) => setFromDateTime(event.target.value)} />
                    </label>
                    <label className="admin-filter-field">
                        <span>종료 시각</span>
                        <input type="datetime-local" value={toDateTime} onChange={(event) => setToDateTime(event.target.value)} />
                    </label>
                    <div className="admin-filter-actions">
                        <button type="submit" className="primary-button">필터 적용</button>
                        <button type="button" className="secondary-button" onClick={clearFilters}>초기화</button>
                    </div>
                </form>

                <div className="admin-applied-filters" aria-label="현재 적용된 검색 조건">
                    <span>적용 조건</span>
                    {hasAppliedFilters ? activeFilters.map((filter) => (
                        <strong key={filter.label}>{filter.label}: {filter.value}</strong>
                    )) : <p>전체 조치 이력</p>}
                </div>
            </section>

            <StateMessage type="error">{error}</StateMessage>

            <section className="admin-audit-list-section" aria-labelledby="admin-audit-list-title" aria-busy={loading || loadingMore}>
                <div className="admin-user-list-heading">
                    <div>
                        <span>Action records</span>
                        <h2 id="admin-audit-list-title">조치 이력</h2>
                    </div>
                    {!loading && <strong>{logs.length}건 표시</strong>}
                </div>

                {loading && logs.length === 0 ? (
                    <EmptyState loading title="감사로그 로딩 중" description="관리자 조치 이력을 불러오고 있습니다." />
                ) : error && logs.length === 0 ? (
                    <div className="admin-user-state-panel">
                        <EmptyState title="감사로그를 표시할 수 없습니다." description="오류 내용을 확인한 뒤 다시 시도해 주세요." />
                        <button type="button" className="secondary-button" onClick={loadLogs}>다시 시도</button>
                    </div>
                ) : logs.length === 0 ? (
                    <div className="admin-user-state-panel">
                        <EmptyState
                            title={hasAppliedFilters ? "검색 조건에 맞는 감사로그가 없습니다." : "표시할 감사로그가 없습니다."}
                            description={hasAppliedFilters ? "조건을 바꾸거나 초기화해 전체 이력을 확인하세요." : "아직 관리자 조치 이력이 없습니다."}
                        />
                        {hasAppliedFilters && <button type="button" className="secondary-button" onClick={clearFilters}>검색 조건 초기화</button>}
                    </div>
                ) : (
                    <div className="admin-user-table-wrap">
                        <table className="admin-user-table">
                            <caption>관리자 조치 이력</caption>
                            <thead>
                                <tr>
                                    <th scope="col">시각</th>
                                    <th scope="col">관리자</th>
                                    <th scope="col">작업</th>
                                    <th scope="col">대상 유형</th>
                                    <th scope="col">대상 ID</th>
                                    <th scope="col">상세</th>
                                    <th scope="col">사유</th>
                                    <th scope="col">인시던트 ID</th>
                                    <th scope="col">요청 ID</th>
                                </tr>
                            </thead>
                            <tbody>
                                {logs.map((log, index) => (
                                    <motion.tr
                                        key={log.id}
                                        initial={prefersReducedMotion ? false : { opacity: 0, y: 8 }}
                                        animate={{ opacity: 1, y: 0 }}
                                        transition={{ duration: 0.28, delay: Math.min(index * 0.035, 0.18), ease: EASE_OUT }}
                                    >
                                        <td data-label="시각">{formatDateTime(log.createdAt)}</td>
                                        <td data-label="관리자">{log.adminEmail}</td>
                                        <td data-label="작업">{formatAction(log.action)}</td>
                                        <td data-label="대상 유형">{formatTargetType(log.targetType)}</td>
                                        <td data-label="대상 ID">{log.targetId}</td>
                                        <td data-label="상세">{log.detail || "-"}</td>
                                        <td data-label="사유">{log.reason || "-"}</td>
                                        <td data-label="인시던트 ID">{log.incidentId || "-"}</td>
                                        <td data-label="요청 ID">{log.requestId || "-"}</td>
                                    </motion.tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}

                {hasMore && !loading && (
                    <div className="admin-user-load-more">
                        <span>현재 {logs.length}건을 표시하고 있습니다.</span>
                        <button type="button" className="secondary-button" onClick={loadMoreLogs} disabled={loadingMore}>
                            {loadingMore ? "불러오는 중..." : "더 보기"}
                        </button>
                    </div>
                )}
            </section>
        </section>
    );
}

function getActiveFilters(filters) {
    const labels = {
        adminEmail: "관리자 이메일",
        action: "작업",
        targetType: "대상 유형",
        targetId: "대상 ID",
        from: "시작 시각",
        to: "종료 시각",
    };

    return Object.entries(filters)
        .filter(([, value]) => Boolean(value))
        .map(([key, value]) => ({
            label: labels[key],
            value: key === "action" ? ACTION_LABELS[value] || value
                : key === "targetType" ? TARGET_TYPE_LABELS[value] || value
                : value,
        }));
}

function AdminAuditIcon({ name }) {
    const paths = {
        log: "M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm8 1.5V8h4.5L14 3.5ZM7 12h10v1.6H7V12Zm0 4h10v1.6H7V16Zm0-8h5v1.6H7V8Z",
        refresh: "M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.74 10h-2.09A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h8V3l-3.35 3.35Z",
        search: "m15.5 14 5 5-1.5 1.5-5-5v-.79l-.27-.28a6.5 6.5 0 1 1 .7-.7l.28.27h.79ZM5 9.5A4.5 4.5 0 1 0 14 9.5a4.5 4.5 0 0 0-9 0Z",
    };

    return (
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path fill="currentColor" d={paths[name]} />
        </svg>
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

export default AdminAuditLogPage;
