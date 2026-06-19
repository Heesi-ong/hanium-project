// 기존 운영 상태 화면으로 시스템 readiness와 문제 분석 작업 재시도를 관리한다.
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { getAdminProblemJobs, getAdminStatus, retryAdminProblemJob } from "../api/adminApi";
import StateMessage from "../components/StateMessage";
import Button from "../components/ui/Button";
import StatusBadge from "../components/ui/StatusBadge";
import { SystemCheckCard } from "../features/admin/AdminDashboardCards";
import "./AdminPage.css";

const statusText = (ok) => (ok ? "정상" : "점검 필요");
const safeNumber = (value) => (typeof value === "number" ? value.toLocaleString() : "-");

export default function AdminSystemPage() {
  const [status, setStatus] = useState(null);
  const [statusError, setStatusError] = useState("");
  const [statusLoading, setStatusLoading] = useState(true);
  const [jobs, setJobs] = useState([]);
  const [jobsError, setJobsError] = useState("");
  const [jobsLoading, setJobsLoading] = useState(true);
  const [retryingId, setRetryingId] = useState("");
  const statusController = useRef(null);
  const jobsController = useRef(null);

  const loadStatus = useCallback(async () => {
    statusController.current?.abort();
    const controller = new AbortController();
    statusController.current = controller;
    setStatusLoading(true);
    try {
      setStatusError("");
      const nextStatus = await getAdminStatus(controller.signal);
      setStatus(nextStatus);
    } catch (requestError) {
      if (requestError.name !== "AbortError") setStatusError(requestError.message);
    } finally {
      if (statusController.current === controller) {
        statusController.current = null;
        setStatusLoading(false);
      }
    }
  }, []);

  const loadJobs = useCallback(async () => {
    jobsController.current?.abort();
    const controller = new AbortController();
    jobsController.current = controller;
    setJobsLoading(true);
    try {
      setJobsError("");
      const problemJobs = await getAdminProblemJobs(controller.signal);
      setJobs(problemJobs.jobs);
    } catch (requestError) {
      if (requestError.name !== "AbortError") setJobsError(requestError.message);
    } finally {
      if (jobsController.current === controller) {
        jobsController.current = null;
        setJobsLoading(false);
      }
    }
  }, []);

  const loadAll = useCallback(() => {
    loadStatus();
    loadJobs();
  }, [loadJobs, loadStatus]);

  const retryJob = async (jobId) => {
    setRetryingId(jobId);
    try {
      await retryAdminProblemJob(jobId);
      await loadJobs();
    } catch (requestError) {
      setJobsError(requestError.message);
    } finally {
      setRetryingId("");
    }
  };

  useEffect(() => {
    loadAll();
    const intervalId = window.setInterval(() => {
      if (!document.hidden) loadAll();
    }, 10000);
    return () => {
      statusController.current?.abort();
      jobsController.current?.abort();
      window.clearInterval(intervalId);
    };
  }, [loadAll]);

  const checks = status?.checks;
  const loading = statusLoading || jobsLoading;
  const modelFiles = checks?.models?.files || [];
  const missingStorage = checks?.storage?.missing || [];
  return (
    <main className="page admin-page">
      <div className="dashboard-header">
        <div>
          <h1 className="page-title">서비스 운영 상태</h1>
          <p className="dashboard-subtitle">DB, 분석 워커, Ollama, 작업 큐와 디스크 상태입니다.</p>
        </div>
        <div className="admin-header-actions">
          <Link className="button secondary" to="/admin">
            관리자 대시보드
          </Link>
          <Button disabled={loading} onClick={loadAll}>
            {loading ? "확인 중..." : "상태 새로고침"}
          </Button>
        </div>
      </div>

      {statusError && (
        <StateMessage type="error" title="운영 상태를 확인하지 못했습니다.">
          {statusError}
        </StateMessage>
      )}

      {checks && (
        <>
          <StateMessage type={status.status === "ready" ? "success" : "error"} compact>
            전체 서비스 상태: {status.status === "ready" ? "준비 완료" : "점검 필요"}
          </StateMessage>
          <div className="admin-status-grid">
            <SystemCheckCard title="데이터베이스" ok={checks.database.ok}>
              <p>연결 상태: {statusText(checks.database.ok)}</p>
            </SystemCheckCard>
            <SystemCheckCard title="분석 워커" ok={checks.worker.ok}>
              <p>
                활성 {checks.worker.active_worker_count} / 설정 {checks.worker.worker_count}
              </p>
              <p>유지보수: {checks.worker.maintenance_running ? "실행 중" : "중단"}</p>
              <p>heartbeat: {checks.worker.worker_heartbeat_stale ? "정체" : "정상"}</p>
              <p>유지보수 상태: {checks.worker.maintenance_stale ? "정체" : "정상"}</p>
            </SystemCheckCard>
            <SystemCheckCard title="Ollama" ok={checks.ollama.ok}>
              <p>{checks.ollama.configured_model}</p>
            </SystemCheckCard>
            <SystemCheckCard
              title="분석 작업 큐"
              ok={checks.queue.ok ?? checks.queue.stalled === 0}
            >
              <p>대기 {checks.queue.queued}건</p>
              <p>처리 {checks.queue.processing}건</p>
              <p>실패 {checks.queue.failed}건</p>
              <p>정체 {checks.queue.stalled}건</p>
            </SystemCheckCard>
            <SystemCheckCard title="스토리지" ok={checks.storage?.ok ?? true}>
              <p>누락 경로 {missingStorage.length}개</p>
              {missingStorage.length > 0 && <p>{missingStorage.join(", ")}</p>}
            </SystemCheckCard>
            <SystemCheckCard title="분석 모델 파일" ok={checks.models?.ok ?? true}>
              <p>확인된 모델 {modelFiles.filter((file) => file.exists).length}개</p>
              <p>누락 모델 {(checks.models?.missing || []).length}개</p>
            </SystemCheckCard>
            <SystemCheckCard title="디스크" ok={checks.disk.ok}>
              <p>여유 공간 {safeNumber(checks.disk.free_mb)}MB</p>
              <p>최소 기준 {safeNumber(checks.disk.minimum_free_mb)}MB</p>
            </SystemCheckCard>
          </div>
        </>
      )}
      <section className="card admin-problem-section">
        <div className="admin-section-heading">
          <div>
            <h2>실패 및 정체 분석 작업</h2>
            <p>사용자 이메일 없이 작업 상태와 재시도 가능 여부만 확인합니다.</p>
          </div>
          <span className="admin-job-count">{jobs.length}건</span>
        </div>
        {jobsError && (
          <StateMessage type="error" compact title="문제 작업을 확인하지 못했습니다.">
            {jobsError}
            <Button variant="secondary" onClick={loadJobs}>
              문제 작업 다시 불러오기
            </Button>
          </StateMessage>
        )}
        {jobsLoading ? (
          <p>문제 작업을 확인하는 중입니다.</p>
        ) : jobs.length === 0 ? (
          <p>확인할 문제 작업이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>작업 ID</th>
                  <th>상태 / 단계</th>
                  <th>진행률</th>
                  <th>시도</th>
                  <th>오류</th>
                  <th>heartbeat</th>
                  <th>작업</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((job) => (
                  <tr key={job.result_id}>
                    <td>{job.result_id}</td>
                    <td>
                      <StatusBadge status={job.status} />{" "}
                      <span className="admin-stage-text">{job.stage}</span>
                    </td>
                    <td>{job.progress}%</td>
                    <td>
                      {job.attempt_count}/{job.max_attempts}
                    </td>
                    <td>{job.public_error || "-"}</td>
                    <td>{job.last_heartbeat_at || "-"}</td>
                    <td>
                      <button
                        className="button secondary"
                        disabled={!job.retry_available || retryingId === job.result_id}
                        onClick={() => retryJob(job.result_id)}
                      >
                        재시도
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
