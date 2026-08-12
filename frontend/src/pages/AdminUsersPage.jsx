import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
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
import { useAuth } from "../context/AuthContext";
import { useConfirm, useReasonPrompt } from "../context/ConfirmContext";

function AdminUsersPage() {
    const { user: currentUser } = useAuth();
    const confirm = useConfirm();
    const promptReason = useReasonPrompt();
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
        <section className="page-section">
            <PageHeader
                eyebrow="Admin"
                title="사용자 관리"
                description="가입자 계정 상태와 사용자별 분석 데이터를 관리합니다."
            />
            <AdminNav />
            <StateMessage type="error">{error}</StateMessage>

            <form className="mb-6 grid gap-3 rounded-2xl border border-white/10 bg-surface-primary p-4 md:grid-cols-4" onSubmit={handleFilterSubmit}>
                <label className="grid gap-1 text-sm font-bold text-text-secondary">
                    이메일
                    <input
                        type="search"
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                        placeholder="이메일 일부 입력"
                    />
                </label>
                <label className="grid gap-1 text-sm font-bold text-text-secondary">
                    상태
                    <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
                        <option value="">전체 상태</option>
                        <option value="ACTIVE">활성</option>
                        <option value="SUSPENDED">정지</option>
                    </select>
                </label>
                <label className="grid gap-1 text-sm font-bold text-text-secondary">
                    권한
                    <select value={roleFilter} onChange={(event) => setRoleFilter(event.target.value)}>
                        <option value="">전체 권한</option>
                        <option value="USER">일반 사용자</option>
                        <option value="ADMIN">관리자</option>
                    </select>
                </label>
                <div className="flex items-end gap-2">
                    <button type="submit" className="secondary-button">검색</button>
                    <button type="button" className="secondary-button" onClick={clearFilters}>초기화</button>
                </div>
            </form>

            {loading ? (
                <EmptyState loading title="사용자 목록 로딩 중" description="잠시만 기다려 주세요." />
            ) : users.length === 0 ? (
                <EmptyState title="표시할 사용자가 없습니다." description="아직 가입한 사용자가 없습니다." />
            ) : (
                <div className="pose-frame-table-wrap">
                    <div className="overflow-x-auto">
                        <table className="pose-frame-table">
                            <thead>
                                <tr>
                                    <th>이메일</th>
                                    <th>권한</th>
                                    <th>상태</th>
                                    <th>가입일</th>
                                    <th>온보딩</th>
                                    <th>분석 작업 수</th>
                                    <th>상세</th>
                                    <th>관리</th>
                                </tr>
                            </thead>
                            <tbody>
                                {users.map((user) => (
                                    <tr key={user.id}>
                                        <td>{user.email}</td>
                                        <td>{user.role === "ADMIN" ? "관리자" : "일반 사용자"}</td>
                                        <td>{user.status === "SUSPENDED" ? "정지" : "활성"}</td>
                                        <td>{formatDateTime(user.createdAt)}</td>
                                        <td>{user.onboardingCompleted ? "완료" : "미완료"}</td>
                                        <td>{user.analysisJobCount}</td>
                                        <td>
                                            <Link to={`/admin/users/${user.id}`} className="secondary-button">
                                                상세 보기
                                            </Link>
                                        </td>
                                        <td>
                                            {currentUser?.id === user.id ? (
                                                <span>-</span>
                                            ) : (
                                                <div className="flex flex-wrap gap-2">
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
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            <div className="button-row">
                {hasMore && (
                    <button type="button" className="secondary-button" onClick={loadMoreUsers} disabled={loadingMore}>
                        {loadingMore ? "불러오는 중..." : "더 보기"}
                    </button>
                )}
                <button type="button" className="secondary-button" onClick={loadUsers} disabled={loading}>
                    목록 새로고침
                </button>
            </div>
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

export default AdminUsersPage;
