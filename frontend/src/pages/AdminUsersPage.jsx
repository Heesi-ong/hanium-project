import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import {
    activateAdminUser,
    forceWithdrawAdminUser,
    getAdminUsers,
    suspendAdminUser,
} from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import AdminNav from "../components/admin/AdminNav";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { EASE_OUT } from "../components/motion/animationVariants";
import { useAuth } from "../context/AuthContext";
import { useConfirm, useReasonPrompt } from "../context/ConfirmContext";

function AdminUsersPage() {
    const { user: currentUser } = useAuth();
    const confirm = useConfirm();
    const promptReason = useReasonPrompt();
    const prefersReducedMotion = useReducedMotion();
    const [users, setUsers] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [actionUserId, setActionUserId] = useState("");
    const [error, setError] = useState("");
    const [email, setEmail] = useState("");
    const [statusFilter, setStatusFilter] = useState("");
    const [roleFilter, setRoleFilter] = useState("");
    const [appliedFilters, setAppliedFilters] = useState({});
    const activeFilters = getActiveFilters(appliedFilters);
    const hasAppliedFilters = activeFilters.length > 0;

    const loadUsers = useCallback(async () => {
        try {
            setLoading(true);
            setError("");
            const response = await getAdminUsers({ page: 0, ...appliedFilters });
            setUsers(response.data?.content || []);
            setPage(0);
            setHasMore(response.data?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(requestError, "사용자 목록을 불러오는 중 오류가 발생했습니다."));
        } finally {
            setLoading(false);
        }
    }, [appliedFilters]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 관리자 사용자 목록을 최초 진입 시 불러옵니다.
        loadUsers();
    }, [loadUsers]);

    async function loadMoreUsers() {
        try {
            setLoadingMore(true);
            setError("");
            const nextPage = page + 1;
            const response = await getAdminUsers({ page: nextPage, ...appliedFilters });
            setUsers((previous) => [...previous, ...(response.data?.content || [])]);
            setPage(nextPage);
            setHasMore(response.data?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(requestError, "사용자 목록을 더 불러오는 중 오류가 발생했습니다."));
        } finally {
            setLoadingMore(false);
        }
    }

    async function handleToggleStatus(targetUser) {
        const isSuspending = targetUser.status !== "SUSPENDED";

        // 활성화는 파괴적 조치가 아니라 사유 입력 없이 확인만 받고, 정지는 파괴적
        // 조치라 사유를 필수로 받는다(P2-03).
        let actionContext = null;
        if (isSuspending) {
            actionContext = await promptReason(
                `${targetUser.email} 계정을 정지합니다. 이 계정으로는 로그인할 수 없게 됩니다.`
            );
            if (!actionContext) {
                return;
            }
        } else {
            const confirmed = await confirm(`${targetUser.email} 계정을 다시 활성화하시겠습니까?`);
            if (!confirmed) {
                return;
            }
        }

        try {
            setActionUserId(targetUser.id);
            setError("");
            if (isSuspending) {
                await suspendAdminUser(targetUser.id, actionContext);
            } else {
                await activateAdminUser(targetUser.id);
            }
            setUsers((previous) => previous.map((item) =>
                item.id === targetUser.id
                    ? { ...item, status: isSuspending ? "SUSPENDED" : "ACTIVE" }
                    : item
            ));
        } catch (requestError) {
            setError(getErrorMessage(requestError, "계정 상태를 변경하는 중 오류가 발생했습니다."));
        } finally {
            setActionUserId("");
        }
    }

    async function handleForceWithdraw(targetUser) {
        const actionContext = await promptReason(
            `${targetUser.email} 계정을 강제 탈퇴시킵니다. 소유한 분석 데이터가 모두 삭제되며 되돌릴 수 없습니다.`
        );
        if (!actionContext) {
            return;
        }

        try {
            setActionUserId(targetUser.id);
            setError("");
            await forceWithdrawAdminUser(targetUser.id, actionContext);
            setUsers((previous) => previous.filter((item) => item.id !== targetUser.id));
        } catch (requestError) {
            setError(getErrorMessage(requestError, "계정을 강제 탈퇴시키는 중 오류가 발생했습니다."));
        } finally {
            setActionUserId("");
        }
    }

    function handleFilterSubmit(event) {
        event.preventDefault();
        setAppliedFilters({
            email: email.trim() || undefined,
            status: statusFilter || undefined,
            role: roleFilter || undefined,
        });
    }

    function clearFilters() {
        setEmail("");
        setStatusFilter("");
        setRoleFilter("");
        setAppliedFilters({});
    }

    return (
        <section className="page-section admin-users-page">
            <header className="admin-page-hero admin-users-hero">
                <div className="admin-page-hero-icon" aria-hidden="true">
                    <AdminUsersIcon name="users" />
                </div>
                <PageHeader
                    eyebrow="Admin directory"
                    title="사용자 관리"
                    description="가입자 계정 상태와 사용자별 분석 데이터를 확인하고, 필요한 관리 작업을 안전하게 수행합니다."
                />
                <div className="admin-page-hero-meta" aria-live="polite">
                    <span>현재 목록</span>
                    <strong>{loading && users.length === 0 ? "불러오는 중" : `${users.length}명 표시`}</strong>
                    <p>{hasMore ? "추가 사용자 목록이 있습니다." : "불러온 목록의 마지막입니다."}</p>
                </div>
            </header>
            <AdminNav />

            <section className="admin-user-workspace" aria-labelledby="admin-user-filter-title">
                <div className="admin-user-workspace-heading">
                    <div>
                        <span>Search & filter</span>
                        <h2 id="admin-user-filter-title">사용자 디렉터리</h2>
                        <p>이메일, 계정 상태, 권한을 조합해 서버 목록을 조회합니다.</p>
                    </div>
                    <button type="button" className="secondary-button admin-list-refresh" onClick={loadUsers} disabled={loading}>
                        <AdminUsersIcon name="refresh" />
                        {loading ? "새로고침 중..." : "목록 새로고침"}
                    </button>
                </div>

                <form className="admin-user-filter-form" onSubmit={handleFilterSubmit}>
                    <label className="admin-filter-field is-search">
                        <span>이메일</span>
                        <div className="admin-filter-input-wrap">
                            <AdminUsersIcon name="search" />
                            <input
                                type="search"
                                value={email}
                                onChange={(event) => setEmail(event.target.value)}
                                placeholder="이메일 일부 입력"
                            />
                        </div>
                    </label>
                    <label className="admin-filter-field">
                        <span>상태</span>
                        <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
                            <option value="">전체 상태</option>
                            <option value="ACTIVE">활성</option>
                            <option value="SUSPENDED">정지</option>
                        </select>
                    </label>
                    <label className="admin-filter-field">
                        <span>권한</span>
                        <select value={roleFilter} onChange={(event) => setRoleFilter(event.target.value)}>
                            <option value="">전체 권한</option>
                            <option value="USER">일반 사용자</option>
                            <option value="ADMIN">관리자</option>
                        </select>
                    </label>
                    <div className="admin-filter-actions">
                        <button type="submit" className="primary-button">검색</button>
                        <button type="button" className="secondary-button" onClick={clearFilters}>초기화</button>
                    </div>
                </form>

                <div className="admin-applied-filters" aria-label="현재 적용된 검색 조건">
                    <span>적용 조건</span>
                    {hasAppliedFilters ? activeFilters.map((filter) => (
                        <strong key={filter.label}>{filter.label}: {filter.value}</strong>
                    )) : <p>전체 사용자</p>}
                </div>
            </section>

            <StateMessage type="error">{error}</StateMessage>

            <section className="admin-user-list-section" aria-labelledby="admin-user-list-title" aria-busy={loading || loadingMore}>
                <div className="admin-user-list-heading">
                    <div>
                        <span>Account records</span>
                        <h2 id="admin-user-list-title">사용자 목록</h2>
                    </div>
                    {!loading && <strong>{users.length}명 표시</strong>}
                </div>

                {loading ? (
                    <EmptyState loading title="사용자 목록 로딩 중" description="계정 상태와 분석 작업 수를 확인하고 있습니다." />
                ) : error && users.length === 0 ? (
                    <div className="admin-user-state-panel">
                        <EmptyState title="사용자 목록을 표시할 수 없습니다." description="오류 내용을 확인한 뒤 다시 시도해 주세요." />
                        <button type="button" className="secondary-button" onClick={loadUsers}>다시 시도</button>
                    </div>
                ) : users.length === 0 ? (
                    <div className="admin-user-state-panel">
                        <EmptyState
                            title={hasAppliedFilters ? "검색 조건에 맞는 사용자가 없습니다." : "표시할 사용자가 없습니다."}
                            description={hasAppliedFilters ? "조건을 바꾸거나 초기화해 전체 사용자를 확인하세요." : "아직 가입한 사용자가 없습니다."}
                        />
                        {hasAppliedFilters && <button type="button" className="secondary-button" onClick={clearFilters}>검색 조건 초기화</button>}
                    </div>
                ) : (
                    <div className="admin-user-table-wrap">
                        <table className="admin-user-table">
                            <caption>관리자 사용자 목록</caption>
                            <thead>
                                <tr>
                                    <th scope="col">사용자</th>
                                    <th scope="col">권한</th>
                                    <th scope="col">상태</th>
                                    <th scope="col">가입일</th>
                                    <th scope="col">온보딩</th>
                                    <th scope="col">분석</th>
                                    <th scope="col">상세</th>
                                    <th scope="col">관리</th>
                                </tr>
                            </thead>
                            <tbody>
                                {users.map((user, index) => (
                                    <motion.tr
                                        key={user.id}
                                        initial={prefersReducedMotion ? false : { opacity: 0, y: 8 }}
                                        animate={{ opacity: 1, y: 0 }}
                                        transition={{ duration: 0.28, delay: Math.min(index * 0.035, 0.18), ease: EASE_OUT }}
                                    >
                                        <td data-label="사용자">
                                            <div className="admin-user-identity">
                                                <span aria-hidden="true">{getUserInitial(user.email)}</span>
                                                <div>
                                                    <strong title={user.email}>{user.email}</strong>
                                                    <small>사용자 ID {user.id}</small>
                                                </div>
                                            </div>
                                        </td>
                                        <td data-label="권한">
                                            <span className={`admin-role-pill is-${user.role === "ADMIN" ? "admin" : "user"}`}>
                                                {user.role === "ADMIN" ? "관리자" : "일반 사용자"}
                                            </span>
                                        </td>
                                        <td data-label="상태">
                                            <span className={`admin-account-status is-${user.status === "SUSPENDED" ? "suspended" : "active"}`}>
                                                <i aria-hidden="true" />
                                                {user.status === "SUSPENDED" ? "정지" : "활성"}
                                            </span>
                                        </td>
                                        <td data-label="가입일" className="admin-user-date">{formatDateTime(user.createdAt)}</td>
                                        <td data-label="온보딩">
                                            <span className={`admin-onboarding-status is-${user.onboardingCompleted ? "complete" : "pending"}`}>
                                                {user.onboardingCompleted ? "완료" : "미완료"}
                                            </span>
                                        </td>
                                        <td data-label="분석" className="admin-job-count">
                                            <span className="admin-job-count-value">
                                                <strong>{user.analysisJobCount ?? "-"}</strong>
                                                {typeof user.analysisJobCount === "number" && <span>건</span>}
                                            </span>
                                        </td>
                                        <td data-label="상세">
                                            <Link to={`/admin/users/${user.id}`} className="admin-user-detail-link">
                                                상세 보기
                                                <AdminUsersIcon name="arrow" />
                                            </Link>
                                        </td>
                                        <td data-label="관리">
                                            {currentUser?.id === user.id ? (
                                                <span className="admin-self-protection">
                                                    <AdminUsersIcon name="shield" />
                                                    현재 로그인 계정
                                                </span>
                                            ) : (
                                                <div className="admin-user-actions">
                                                    <button
                                                        type="button"
                                                        className={user.status === "SUSPENDED" ? "secondary-button" : "danger-button"}
                                                        onClick={() => handleToggleStatus(user)}
                                                        disabled={actionUserId === user.id}
                                                    >
                                                        {actionUserId === user.id
                                                            ? "처리 중..."
                                                            : user.status === "SUSPENDED" ? "활성화" : "정지"}
                                                    </button>
                                                    <button
                                                        type="button"
                                                        className="danger-button"
                                                        onClick={() => handleForceWithdraw(user)}
                                                        disabled={actionUserId === user.id}
                                                    >
                                                        {actionUserId === user.id ? "처리 중..." : "강제 탈퇴"}
                                                    </button>
                                                </div>
                                            )}
                                        </td>
                                    </motion.tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}

                {hasMore && !loading && (
                    <div className="admin-user-load-more">
                        <span>현재 {users.length}명을 표시하고 있습니다.</span>
                        <button type="button" className="secondary-button" onClick={loadMoreUsers} disabled={loadingMore}>
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
        email: "이메일",
        status: "상태",
        role: "권한",
    };
    const values = {
        ACTIVE: "활성",
        SUSPENDED: "정지",
        USER: "일반 사용자",
        ADMIN: "관리자",
    };

    return Object.entries(filters)
        .filter(([, value]) => Boolean(value))
        .map(([key, value]) => ({ label: labels[key], value: values[value] || value }));
}

function getUserInitial(email) {
    return typeof email === "string" && email.length > 0 ? email.charAt(0).toUpperCase() : "?";
}

function AdminUsersIcon({ name }) {
    const paths = {
        users: "M9.5 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm6.25-1a2.75 2.75 0 1 0 0-5.5 2.75 2.75 0 0 0 0 5.5ZM3 19.25C3 15.8 5.91 13 9.5 13s6.5 2.8 6.5 6.25V20H3v-.75Zm13.37-6.09A6.95 6.95 0 0 1 18 17.7V20h3v-.6c0-3.01-1.98-5.55-4.63-6.24Z",
        refresh: "M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.74 10h-2.09A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h8V3l-3.35 3.35Z",
        search: "m15.5 14 5 5-1.5 1.5-5-5v-.79l-.27-.28a6.5 6.5 0 1 1 .7-.7l.28.27h.79ZM5 9.5A4.5 4.5 0 1 0 14 9.5a4.5 4.5 0 0 0-9 0Z",
        arrow: "m13.17 5.59 1.42-1.42L22.41 12l-7.82 7.83-1.42-1.42L18.59 13H2v-2h16.59l-5.42-5.41Z",
        shield: "M12 2 4 5v6c0 5.05 3.41 9.74 8 11 4.59-1.26 8-5.95 8-11V5l-8-3Zm-1 14-3-3 1.4-1.4 1.6 1.59 3.6-3.59L16 11l-5 5Z",
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

export default AdminUsersPage;
