// 사용자 프로필, 비밀번호, 세션, 저장소 사용량, 계정 삭제 기능을 제공한다.
import { useEffect, useState } from "react";

import {
  changePassword,
  deleteAccount,
  exportUserData,
  getStorageUsage,
  logoutAll,
  updateProfile,
} from "../api/accountApi";
import StateMessage from "../components/StateMessage";
import ActionDialog from "../components/ActionDialog";
import "./AccountPage.css";

export default function AccountPage({ user, onUserChange, onSignedOut }) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [passwords, setPasswords] = useState({ current_password: "", new_password: "" });
  const [deletePassword, setDeletePassword] = useState("");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [storage, setStorage] = useState(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [busyAction, setBusyAction] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    getStorageUsage(controller.signal)
      .then((result) => setStorage(result.storage))
      .catch((requestError) => {
        if (requestError.name !== "AbortError") setError(requestError.message);
      });
    return () => controller.abort();
  }, []);

  const downloadExport = async () => {
    const data = await exportUserData();
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `speakinsight-data-${new Date().toISOString().slice(0, 10)}.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const run = async (action, message, actionKey = "default") => {
    if (busyAction) return;
    setBusyAction(actionKey);
    setError("");
    setNotice("");
    try {
      await action();
      if (message) setNotice(message);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setBusyAction("");
    }
  };

  return (
    <main className="page account-page">
      <h1 className="page-title">계정 관리</h1>
      {notice && (
        <StateMessage type="success" compact>
          {notice}
        </StateMessage>
      )}
      {error && (
        <StateMessage type="error" compact>
          {error}
        </StateMessage>
      )}
      <section className="card account-section">
        <h2>프로필</h2>
        <p>{user.email}</p>
        <label htmlFor="account-display-name">표시 이름</label>
        <input
          id="account-display-name"
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
        />
        <button
          className="button"
          onClick={() =>
            run(
              async () => {
                const result = await updateProfile({ display_name: displayName });
                onUserChange(result.user);
              },
              "표시 이름을 변경했습니다.",
              "profile",
            )
          }
          disabled={Boolean(busyAction) || displayName.trim().length < 2}
        >
          {busyAction === "profile" ? "변경 중..." : "표시 이름 변경"}
        </button>
      </section>
      <section className="card account-section">
        <h2>비밀번호 변경</h2>
        <label htmlFor="account-current-password">현재 비밀번호</label>
        <input
          id="account-current-password"
          type="password"
          placeholder="현재 비밀번호"
          value={passwords.current_password}
          onChange={(event) => setPasswords({ ...passwords, current_password: event.target.value })}
        />
        <label htmlFor="account-new-password">새 비밀번호</label>
        <input
          id="account-new-password"
          type="password"
          placeholder="새 비밀번호 (8자 이상)"
          value={passwords.new_password}
          onChange={(event) => setPasswords({ ...passwords, new_password: event.target.value })}
        />
        <button
          className="button"
          onClick={() =>
            run(
              async () => {
                await changePassword(passwords);
                setPasswords({ current_password: "", new_password: "" });
              },
              "비밀번호를 변경했습니다.",
              "password",
            )
          }
          disabled={
            Boolean(busyAction) || !passwords.current_password || passwords.new_password.length < 8
          }
        >
          {busyAction === "password" ? "변경 중..." : "비밀번호 변경"}
        </button>
      </section>
      <section className="card account-section">
        <h2>세션 및 계정</h2>
        {storage && (
          <div>
            <strong>저장 공간</strong>
            <p>
              {(storage.used_bytes / 1024 / 1024).toFixed(1)}MB /{" "}
              {(storage.quota_bytes / 1024 / 1024).toFixed(0)}MB
            </p>
            <p>
              진행 중 분석 {storage.active_analysis_count}개 / 최대 {storage.max_active_analyses}개
            </p>
          </div>
        )}
        <button
          className="button secondary"
          onClick={() => run(downloadExport, "사용자 데이터를 내려받았습니다.", "export")}
          disabled={Boolean(busyAction)}
        >
          {busyAction === "export" ? "내보내는 중..." : "내 데이터 내보내기"}
        </button>
        <button
          className="button secondary"
          onClick={() =>
            run(
              async () => {
                await logoutAll();
                onSignedOut();
              },
              "",
              "logout-all",
            )
          }
          disabled={Boolean(busyAction)}
        >
          {busyAction === "logout-all" ? "로그아웃 중..." : "모든 기기에서 로그아웃"}
        </button>
        <label htmlFor="account-delete-password">탈퇴 확인 비밀번호</label>
        <input
          id="account-delete-password"
          type="password"
          placeholder="탈퇴 확인 비밀번호"
          value={deletePassword}
          onChange={(event) => setDeletePassword(event.target.value)}
        />
        <button
          className="button danger"
          disabled={Boolean(busyAction) || !deletePassword}
          onClick={() => setDeleteDialogOpen(true)}
        >
          계정 탈퇴
        </button>
      </section>
      <ActionDialog
        open={deleteDialogOpen}
        title="계정과 모든 데이터를 삭제하시겠습니까?"
        description="이 작업은 취소할 수 없습니다."
        confirmLabel="계정 삭제"
        danger
        onCancel={() => setDeleteDialogOpen(false)}
        onConfirm={() => {
          setDeleteDialogOpen(false);
          run(
            async () => {
              await deleteAccount({ password: deletePassword });
              onSignedOut();
            },
            "",
            "delete",
          );
        }}
      />
    </main>
  );
}
