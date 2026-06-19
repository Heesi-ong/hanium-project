import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import { login, register } from "../api/accountApi";
import StateMessage from "../components/StateMessage";
import "./LoginPage.css";

export default function LoginPage({ onAuthenticated }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState("login");
  const [form, setForm] = useState({ email: "", password: "", display_name: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const update = (event) => setForm({ ...form, [event.target.name]: event.target.value });

  const submit = async (event) => {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const result =
        mode === "login"
          ? await login({ email: form.email, password: form.password })
          : await register(form);
      onAuthenticated(result.user);
      navigate(location.state?.from || "/upload", { replace: true });
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="page auth-page">
      <section className="card auth-card">
        <h1 className="page-title">{mode === "login" ? "로그인" : "회원가입"}</h1>
        <p className="muted-text">로그인 후 로컬 Ollama 모델과 대화할 수 있습니다.</p>
        <form className="auth-form" onSubmit={submit}>
          {mode === "register" && (
            <label>
              표시 이름
              <input
                name="display_name"
                value={form.display_name}
                onChange={update}
                minLength="2"
                required
              />
            </label>
          )}
          <label>
            이메일
            <input name="email" type="email" value={form.email} onChange={update} required />
          </label>
          <label>
            비밀번호
            <input
              name="password"
              type="password"
              value={form.password}
              onChange={update}
              minLength={mode === "register" ? 8 : 1}
              required
            />
          </label>
          {error && (
            <StateMessage type="error" compact>
              {error}
            </StateMessage>
          )}
          <button className="button" disabled={submitting}>
            {submitting ? "처리 중..." : mode === "login" ? "로그인" : "회원가입"}
          </button>
        </form>
        <button
          className="text-button"
          onClick={() => setMode(mode === "login" ? "register" : "login")}
        >
          {mode === "login" ? "계정이 없나요? 회원가입" : "이미 계정이 있나요? 로그인"}
        </button>
      </section>
    </main>
  );
}
