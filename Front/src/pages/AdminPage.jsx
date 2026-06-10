import React, { useCallback, useEffect, useRef, useState } from "react";

import { getAdminStatus } from "../api/adminApi";
import StateMessage from "../components/StateMessage";
import "./AdminPage.css";

const statusText = (ok) => (ok ? "정상" : "점검 필요");

export default function AdminPage() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const requestController = useRef(null);

  const loadStatus = useCallback(async () => {
    requestController.current?.abort();
    const controller = new AbortController();
    requestController.current = controller;
    setLoading(true);
    try {
      setError("");
      setStatus(await getAdminStatus(controller.signal));
    } catch (requestError) {
      if (requestError.name !== "AbortError") setError(requestError.message);
    } finally {
      if (requestController.current === controller) {
        requestController.current = null;
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    loadStatus();
    const intervalId = window.setInterval(() => {
      if (!document.hidden) loadStatus();
    }, 10000);
    return () => {
      requestController.current?.abort();
      window.clearInterval(intervalId);
    };
  }, [loadStatus]);

  const checks = status?.checks;
  return (
    <main className="page admin-page">
      <div className="dashboard-header">
        <div>
          <h1 className="page-title">서비스 운영 상태</h1>
          <p className="dashboard-subtitle">DB, 분석 워커, Ollama, 작업 큐와 디스크 상태입니다.</p>
        </div>
        <button className="button" disabled={loading} onClick={loadStatus}>
          {loading ? "확인 중..." : "상태 새로고침"}
        </button>
      </div>

      {error && (
        <StateMessage type="error" title="운영 상태를 확인하지 못했습니다.">
          {error}
        </StateMessage>
      )}

      {checks && (
        <>
          <StateMessage type={status.status === "ready" ? "success" : "error"} compact>
            전체 서비스 상태: {status.status === "ready" ? "준비 완료" : "점검 필요"}
          </StateMessage>
          <div className="admin-status-grid">
            <article className="card">
              <h2>데이터베이스</h2>
              <strong className={checks.database.ok ? "status-ok" : "status-error"}>
                {statusText(checks.database.ok)}
              </strong>
            </article>
            <article className="card">
              <h2>분석 워커</h2>
              <strong className={checks.worker.ok ? "status-ok" : "status-error"}>
                {statusText(checks.worker.ok)}
              </strong>
              <p>
                활성 {checks.worker.active_worker_count} / 설정 {checks.worker.worker_count}
              </p>
              <p>유지보수: {checks.worker.maintenance_running ? "실행 중" : "중단"}</p>
            </article>
            <article className="card">
              <h2>Ollama</h2>
              <strong className={checks.ollama.ok ? "status-ok" : "status-error"}>
                {statusText(checks.ollama.ok)}
              </strong>
              <p>{checks.ollama.configured_model}</p>
            </article>
            <article className="card">
              <h2>분석 작업 큐</h2>
              <p>대기 {checks.queue.queued}건</p>
              <p>처리 {checks.queue.processing}건</p>
              <p>실패 {checks.queue.failed}건</p>
            </article>
            <article className="card">
              <h2>디스크</h2>
              <strong className={checks.disk.ok ? "status-ok" : "status-error"}>
                {statusText(checks.disk.ok)}
              </strong>
              <p>여유 공간 {checks.disk.free_mb.toLocaleString()}MB</p>
              <p>최소 기준 {checks.disk.minimum_free_mb.toLocaleString()}MB</p>
            </article>
          </div>
        </>
      )}
    </main>
  );
}
