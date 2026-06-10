import { useState } from "react";

import { changePassword, deleteAccount, logoutAll, updateProfile } from "../api/accountApi";
import StateMessage from "../components/StateMessage";
import "./AccountPage.css";

export default function AccountPage({ user, onUserChange, onSignedOut }) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [passwords, setPasswords] = useState({ current_password: "", new_password: "" });
  const [deletePassword, setDeletePassword] = useState("");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  const run = async (action, message) => {
    setError("");
    setNotice("");
    try {
      await action();
      setNotice(message);
    } catch (requestError) {
      setError(requestError.message);
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
            run(async () => {
              const result = await updateProfile({ display_name: displayName });
              onUserChange(result.user);
            }, "표시 이름을 변경했습니다.")
          }
        >
          표시 이름 변경
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
          onClick={() => run(() => changePassword(passwords), "비밀번호를 변경했습니다.")}
        >
          비밀번호 변경
        </button>
      </section>
      <section className="card account-section">
        <h2>세션 및 계정</h2>
        <button
          className="button secondary"
          onClick={() =>
            run(async () => {
              await logoutAll();
              onSignedOut();
            }, "")
          }
        >
          모든 기기에서 로그아웃
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
          onClick={() => {
            if (!window.confirm("계정과 모든 데이터를 삭제하시겠습니까?")) return;
            run(async () => {
              await deleteAccount({ password: deletePassword });
              onSignedOut();
            }, "");
          }}
        >
          계정 탈퇴
        </button>
      </section>
    </main>
  );
}
