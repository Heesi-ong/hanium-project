import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { activateAdminUser, forceWithdrawAdminUser, getAdminStats, getAdminUsers, suspendAdminUser } from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { useAuth } from "../context/AuthContext";
import { useConfirm } from "../context/ConfirmContext";

function AdminDashboardPage() {
    const { user: currentUser } = useAuth();
    const confirm = useConfirm();
    const [stats, setStats] = useState(null);
    const [users, setUsers] = useState([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [actionUserId, setActionUserId] = useState("");
    const [error, setError] = useState("");

    const loadDashboard = useCallback(async () => {
        try {
            const [statsResponse, usersResponse] = await Promise.all([
                getAdminStats(),
                getAdminUsers({ page: 0 }),
            ]);

            setStats(statsResponse.data);
            setUsers(usersResponse.data?.content || []);
            setHasMore(usersResponse.data?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "관리자 대시보드 정보를 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 마운트 시 관리자 API를 호출한 뒤 응답 상태를 화면에 반영해야 합니다.
        loadDashboard();
    }, [loadDashboard]);

    async function loadMoreUsers() {
        try {
            setLoadingMore(true);
            setError("");

            const nextPage = page + 1;
            const usersResponse = await getAdminUsers({ page: nextPage });

            setUsers((prevUsers) => [...prevUsers, ...(usersResponse.data?.content || [])]);
            setPage(nextPage);
            setHasMore(usersResponse.data?.last === false);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "사용자 목록을 더 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoadingMore(false);
        }
    }

    async function handleToggleStatus(targetUser) {
        const isSuspending = targetUser.status !== "SUSPENDED";
        const confirmMessage = isSuspending
            ? `${targetUser.email} 계정을 정지하시겠습니까?`
            : `${targetUser.email} 계정을 다시 활성화하시겠습니까?`;

        const confirmed = await confirm(confirmMessage);
        if (!confirmed) {
            return;
        }

        try {
            setActionUserId(targetUser.id);
            setError("");

            if (isSuspending) {
                await suspendAdminUser(targetUser.id);
            } else {
                await activateAdminUser(targetUser.id);
            }

            setUsers((prevUsers) => prevUsers.map((item) =>
                item.id === targetUser.id
                    ? { ...item, status: isSuspending ? "SUSPENDED" : "ACTIVE" }
                    : item
            ));
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "계정 상태를 변경하는 중 오류가 발생했습니다."
            ));
        } finally {
            setActionUserId("");
        }
    }

    async function handleForceWithdraw(targetUser) {
        const confirmed = await confirm(
            `${targetUser.email} 계정을 강제 탈퇴시키겠습니까? 소유한 분석 데이터가 모두 삭제되며 되돌릴 수 없습니다.`
        );
        if (!confirmed) {
            return;
        }

        try {
            setActionUserId(targetUser.id);
            setError("");

            await forceWithdrawAdminUser(targetUser.id);

            setUsers((prevUsers) => prevUsers.filter((item) => item.id !== targetUser.id));
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "계정을 강제 탈퇴시키는 중 오류가 발생했습니다."
            ));
        } finally {
            setActionUserId("");
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

    function formatRole(role) {
        return role === "ADMIN" ? "관리자" : "일반 사용자";
    }

    if (loading) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Admin"
                    title="관리자 대시보드"
                    description="가입자 현황과 분석 작업 통계를 불러오는 중입니다."
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
                title="관리자 대시보드"
                description="가입자 현황과 분석 작업 통계를 확인합니다. 사용자 개별 분석 결과 내용은 상세 화면에서만 확인할 수 있습니다."
            />

            <StateMessage type="error">{error}</StateMessage>

            <div className="result-summary-grid">
                <article className="summary-card">
                    <span>전체 가입자</span>
                    <strong>{stats?.totalUsers ?? 0}</strong>
                    <p>지금까지 가입한 전체 사용자 수입니다.</p>
                </article>

                <article className="summary-card">
                    <span>관리자</span>
                    <strong>{stats?.adminUsers ?? 0}</strong>
                    <p>ADMIN_EMAILS에 등록된 관리자 계정 수입니다.</p>
                </article>

                <article className="summary-card">
                    <span>전체 분석 작업</span>
                    <strong>{stats?.totalAnalysisJobs ?? 0}</strong>
                    <p>지금까지 접수된 전체 분석 작업 수입니다.</p>
                </article>

                <article className="summary-card">
                    <span>완료된 분석</span>
                    <strong>{stats?.completedAnalysisJobs ?? 0}</strong>
                    <p>정상적으로 완료된 분석 작업 수입니다.</p>
                </article>
            </div>

            {users.length === 0 ? (
                <EmptyState
                    title="표시할 사용자가 없습니다."
                    description="아직 가입한 사용자가 없습니다."
                />
            ) : (
                <div className="pose-frame-table-wrap">
                    <h3>사용자 목록</h3>

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
                                    <td>{formatRole(user.role)}</td>
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
                                            <>
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
                                                {" "}
                                                <button
                                                    type="button"
                                                    className="danger-button"
                                                    onClick={() => handleForceWithdraw(user)}
                                                    disabled={actionUserId === user.id}
                                                >
                                                    {actionUserId === user.id ? "처리 중..." : "강제 탈퇴"}
                                                </button>
                                            </>
                                        )}
                                    </td>
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
                        onClick={loadMoreUsers}
                        disabled={loadingMore}
                    >
                        {loadingMore ? "불러오는 중..." : "더 보기"}
                    </button>
                </div>
            )}
        </section>
    );
}

export default AdminDashboardPage;
