import { type FormEvent, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useStore } from "zustand";
import { ShieldCheck } from "lucide-react";

import { apiClients } from "@/api/clients";
import { findApiProblemError } from "@/api/core/problem";
import { adminAuth } from "@/auth/domains";
import { Button } from "@/components/ui/button";

export function AdminLoginPage() {
  const session = useStore(adminAuth.store, (state) => state.session);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const navigate = useNavigate();
  const location = useLocation();

  if (session?.role === "ROLE_ADMIN") return <Navigate to="/admin" replace />;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const response = await apiClients.admin.authentication.login({
        loginRequestDto: { username, password },
      });
      if (response.role !== "ROLE_ADMIN") {
        throw new Error("该身份不是 Administrator，无法进入运营工作台。");
      }
      adminAuth.store.getState().setSession({
        accessToken: response.accessToken,
        expiresAt: response.expiresAt.toISOString(),
        role: "ROLE_ADMIN",
        userId: response.userId,
        username: response.username,
      });
      const from = (location.state as { from?: unknown } | null)?.from;
      void navigate(
        typeof from === "string" && from.startsWith("/admin") ? from : "/admin",
        { replace: true },
      );
    } catch (caught) {
      const problem = findApiProblemError(caught)?.problem;
      setError(
        problem
          ? `${problem.detail}（请求 ID ${problem.requestId}）`
          : caught instanceof Error
            ? caught.message
            : "管理员登录没有完成。",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="admin-login-shell">
      <a className="skip-link" href="#admin-login-form">
        跳到管理员登录
      </a>
      <section
        className="admin-login-intro"
        aria-labelledby="admin-login-title"
      >
        <div className="brand-lockup brand-lockup-inverse">
          <span className="brand-mark" aria-hidden="true">
            H
          </span>
          <span>
            <strong>HOTSHOP</strong>
            <small>OPERATIONS</small>
          </span>
        </div>
        <div>
          <p className="eyebrow">ADMINISTRATOR BOUNDARY</p>
          <h1 id="admin-login-title">从真实事实出发，定位每一次交易偏差。</h1>
          <p>
            此入口仅接受 Administrator 身份。Access Token 仅驻留当前页面内存，
            最终权限判定始终由 Admin API 完成。
          </p>
        </div>
        <div className="admin-login-signal">
          <ShieldCheck aria-hidden="true" />
          <span>后端权限校验 · 审计留痕 · 可观测定位</span>
        </div>
      </section>
      <section className="admin-login-card" aria-label="管理员登录表单">
        <p className="eyebrow">SECURE ACCESS</p>
        <h2>登录运营工作台</h2>
        <form id="admin-login-form" onSubmit={(event) => void submit(event)}>
          <label className="field">
            <span>管理员用户名</span>
            <input
              required
              minLength={3}
              maxLength={64}
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </label>
          <label className="field">
            <span>密码</span>
            <input
              required
              minLength={8}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {error ? (
            <p className="inline-problem" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? "正在验证身份…" : "以 Administrator 身份登录"}
          </Button>
        </form>
      </section>
    </main>
  );
}
