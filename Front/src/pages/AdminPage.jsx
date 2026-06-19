// 관리자 랜딩페이지에서 개인정보 없는 집계와 허용된 사용자 관리 기능을 제공한다.
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { getAdminMetrics, getAdminUsers, updateAdminUserStatus } from "../api/adminApi";
import ActionDialog from "../components/ActionDialog";
import StateMessage from "../components/StateMessage";
import Button from "../components/ui/Button";
import StatusBadge from "../components/ui/StatusBadge";
import { AdminMetricCard, AdminPolicyPanel } from "../features/admin/AdminDashboardCards";
import "./AdminPage.css";

const USER_PAGE_SIZE = 20;

export default function AdminPage() {
  const [metrics, setMetrics] = useState(null);
  const [users, setUsers] = useState([]);
  const [userTotal, setUserTotal] = useState(0);
  const [userPage, setUserPage] = useState(1);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  const [metricsLoading, setMetricsLoading] = useState(true);
  const [usersLoading, setUsersLoading] = useState(true);
  const [updatingId, setUpdatingId] = useState(null);
  const [pendingStatusUser, setPendingStatusUser] = useState(null);
  const metricsControllerRef = useRef(null);
  const usersControllerRef = useRef(null);

  const loadMetrics = useCallback(async () => {
    metricsControllerRef.current?.abort();
    const controller = new AbortController();
    metricsControllerRef.current = controller;
    setMetricsLoading(true);
    setError("");
    try {
      const nextMetrics = await getAdminMetrics(controller.signal);
      setMetrics(nextMetrics);
    } catch (requestError) {
      if (requestError.name !== "AbortError") setError(requestError.message);
    } finally {
      if (metricsControllerRef.current === controller) {
        metricsControllerRef.current = null;
        setMetricsLoading(false);
      }
    }
  }, []);

  const loadUsers = useCallback(async () => {
    usersControllerRef.current?.abort();
    const controller = new AbortController();
    usersControllerRef.current = controller;
    setUsersLoading(true);
    setError("");
    try {
      const nextUsers = await getAdminUsers(
        {
          search: debouncedSearch,
          status,
          limit: USER_PAGE_SIZE,
          offset: (userPage - 1) * USER_PAGE_SIZE,
        },
        controller.signal,
      );
      setUsers(nextUsers.users || []);
      setUserTotal(nextUsers.total || 0);
    } catch (requestError) {
      if (requestError.name !== "AbortError") setError(requestError.message);
    } finally {
      if (usersControllerRef.current === controller) {
        usersControllerRef.current = null;
        setUsersLoading(false);
      }
    }
  }, [debouncedSearch, status, userPage]);

  const refreshAll = useCallback(() => {
    loadMetrics();
    loadUsers();
  }, [loadMetrics, loadUsers]);

  useEffect(() => {
    loadMetrics();
    return () => metricsControllerRef.current?.abort();
  }, [loadMetrics]);

  useEffect(() => {
    const timerId = window.setTimeout(() => {
      setUserPage(1);
      setDebouncedSearch(search.trim());
    }, 300);
    return () => window.clearTimeout(timerId);
  }, [search]);

  useEffect(() => {
    loadUsers();
    return () => usersControllerRef.current?.abort();
  }, [loadUsers]);

  const changeStatus = async () => {
    if (!pendingStatusUser) return;
    const user = pendingStatusUser;
    const nextStatus = user.status === "active" ? "disabled" : "active";
    setUpdatingId(user.id);
    setError("");
    try {
      await updateAdminUserStatus(user.id, nextStatus);
      setPendingStatusUser(null);
      await Promise.all([loadMetrics(), loadUsers()]);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setUpdatingId(null);
    }
  };

  const analysis = metrics?.analysis;
  const userMetrics = metrics?.users;
  const loading = metricsLoading || usersLoading;
  const totalPages = Math.max(1, Math.ceil(userTotal / USER_PAGE_SIZE));
  const pendingStatus = pendingStatusUser?.status === "active" ? "disabled" : "active";
  const pendingStatusLabel = pendingStatus === "disabled" ? "정지" : "활성화";
  const successTone =
    analysis?.success_rate == null
      ? "neutral"
      : analysis.success_rate >= 80
        ? "success"
        : "warning";
  return (
    <main className="page admin-page">
      <div className="dashboard-header">
        <div>
          <h1 className="page-title">관리자 대시보드</h1>
          <p className="dashboard-subtitle">개인정보를 최소화한 사용자·분석 운영 요약입니다.</p>
        </div>
        <div className="admin-header-actions">
          <Button disabled={loading} onClick={refreshAll}>
            {loading ? "불러오는 중..." : "전체 새로고침"}
          </Button>
          <Link className="button secondary" to="/admin/system">
            시스템 운영 상태
          </Link>
        </div>
      </div>

      {error && (
        <StateMessage type="error" compact>
          {error}
        </StateMessage>
      )}

      {metrics && (
        <>
          <section className="admin-overview-grid" aria-label="관리자 운영 요약">
            <div className="admin-overview-copy">
              <p className="admin-card-label">오늘의 운영 상태</p>
              <h2>
                활성 사용자 {userMetrics.active}명, 완료 분석 {analysis.completed}건을 기준으로 운영
                현황을 확인합니다.
              </h2>
              <p>
                이 화면은 개인정보 없는 집계와 허용된 사용자 상태 변경만 제공합니다. 시스템 장애와
                문제 작업은 운영 상태 화면에서 확인합니다.
              </p>
            </div>
            <AdminPolicyPanel />
          </section>

          <section className="admin-status-grid" aria-label="관리자 요약 통계">
            <AdminMetricCard
              title="사용자"
              tone="info"
              value={`${userMetrics.total}명`}
              details={[`활성 ${userMetrics.active}명`, `정지 ${userMetrics.disabled}명`]}
            />
            <AdminMetricCard
              title="분석"
              tone={analysis.failed > 0 ? "warning" : "success"}
              value={`${analysis.total}건`}
              details={[`완료 ${analysis.completed}건`, `실패 ${analysis.failed}건`]}
            />
            <AdminMetricCard
              title="성공률"
              tone={successTone}
              value={analysis.success_rate == null ? "-" : `${analysis.success_rate}%`}
              details={[`최근 24시간 완료 ${analysis.completed_last_24_hours}건`]}
            />
            <AdminMetricCard
              title="평균 처리 시간"
              tone="neutral"
              value={
                analysis.average_completed_processing_seconds == null
                  ? "-"
                  : `${analysis.average_completed_processing_seconds}초`
              }
              details={[`최근 24시간 신규 사용자 ${userMetrics.created_last_24_hours}명`]}
            />
          </section>
        </>
      )}

      <section className="card admin-users-section">
        <div className="dashboard-header">
          <div>
            <h2>사용자 관리</h2>
            <p>이메일, 가입일, 상태만 표시합니다. 관리자 권한은 CLI에서만 변경할 수 있습니다.</p>
          </div>
          <Button disabled={loading} onClick={refreshAll}>
            {loading ? "불러오는 중..." : "사용자 새로고침"}
          </Button>
        </div>
        <div className="admin-user-filters">
          <label>
            이메일 검색
            <input value={search} onChange={(event) => setSearch(event.target.value)} />
          </label>
          <label>
            상태
            <select
              value={status}
              onChange={(event) => {
                setUserPage(1);
                setStatus(event.target.value);
              }}
            >
              <option value="">전체</option>
              <option value="active">활성</option>
              <option value="disabled">정지</option>
            </select>
          </label>
        </div>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>이메일</th>
                <th>가입일</th>
                <th>상태</th>
                <th>작업</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id}>
                  <td>{user.email}</td>
                  <td>{user.created_at || "-"}</td>
                  <td>
                    <StatusBadge status={user.status === "active" ? "COMPLETED" : "CANCELLED"} />
                  </td>
                  <td>
                    <button
                      className={`button ${user.status === "active" ? "danger" : "secondary"}`}
                      disabled={!user.status_change_allowed || updatingId === user.id}
                      onClick={() => setPendingStatusUser(user)}
                    >
                      {!user.status_change_allowed
                        ? "CLI에서 관리"
                        : user.status === "active"
                          ? "계정 정지"
                          : "계정 활성화"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!loading && users.length === 0 && <p>조건에 맞는 사용자가 없습니다.</p>}
        <div className="admin-pagination" aria-label="관리자 사용자 페이지 이동">
          <button
            className="button secondary"
            disabled={usersLoading || userPage <= 1}
            onClick={() => setUserPage((page) => Math.max(1, page - 1))}
          >
            이전
          </button>
          <span>
            {userPage} / {totalPages} 페이지 · 총 {userTotal}명
          </span>
          <button
            className="button secondary"
            disabled={usersLoading || userPage >= totalPages}
            onClick={() => setUserPage((page) => page + 1)}
          >
            다음
          </button>
        </div>
      </section>
      <ActionDialog
        open={Boolean(pendingStatusUser)}
        title={`계정을 ${pendingStatusLabel}하시겠습니까?`}
        description={`${pendingStatusUser?.email || ""} 계정 상태를 ${pendingStatusLabel}합니다. ${
          pendingStatus === "disabled" ? "정지하면 기존 세션도 만료됩니다." : ""
        }`}
        confirmLabel={`계정 ${pendingStatusLabel}`}
        danger={pendingStatus === "disabled"}
        onCancel={() => setPendingStatusUser(null)}
        onConfirm={changeStatus}
      />
    </main>
  );
}
