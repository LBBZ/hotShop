import { type FormEvent, type KeyboardEvent, useRef, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { ApiProblemError } from "@/api/core/problem";
import { userAuth } from "@/auth/domains";
import { Button } from "@/components/ui/button";

type Mode = "login" | "register";

export function UserAuthPanel() {
  const [mode, setMode] = useState<Mode>("login");
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string>();
  const loginTab = useRef<HTMLButtonElement>(null);
  const registerTab = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();

  const selectTab = (nextMode: Mode, focus: boolean) => {
    setMode(nextMode);
    if (focus) {
      (nextMode === "login" ? loginTab : registerTab).current?.focus();
    }
  };

  const handleTabKeyDown = (
    event: KeyboardEvent<HTMLButtonElement>,
    currentMode: Mode,
  ) => {
    let nextMode: Mode | undefined;
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      nextMode = currentMode === "login" ? "register" : "login";
    } else if (event.key === "Home") {
      nextMode = "login";
    } else if (event.key === "End") {
      nextMode = "register";
    }
    if (!nextMode) return;
    event.preventDefault();
    selectTab(nextMode, true);
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      if (mode === "register") {
        await apiClients.public.authentication.register({
          registerRequestDto: { username, email, password },
        });
      }
      const response = await apiClients.public.authentication.login({
        loginRequestDto: { username, password },
      });
      if (response.role !== "ROLE_USER") {
        throw new Error("登录响应不属于 User 身份域。");
      }
      userAuth.store.getState().setSession({
        accessToken: response.accessToken,
        expiresAt: response.expiresAt.toISOString(),
        role: "ROLE_USER",
        userId: response.userId,
        username: response.username,
      });
      const fromState = (location.state as { from?: unknown } | null)?.from;
      const target =
        params.get("returnTo") ??
        (typeof fromState === "string" ? fromState : "/user");
      void navigate(target.startsWith("/") ? target : "/user", {
        replace: true,
      });
    } catch (error) {
      setProblem(
        error instanceof ApiProblemError
          ? `${error.problem.detail}（请求 ID ${error.problem.requestId}）`
          : error instanceof Error
            ? error.message
            : "身份请求没有完成。",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="auth-stage" aria-labelledby="auth-title">
      <div className="auth-note">
        <p className="eyebrow">USER ACCESS</p>
        <h1 id="auth-title">把身份留在边界内，把交易带回来。</h1>
        <p>
          Access Token 只驻留当前页面内存；刷新恢复依赖 HttpOnly
          Cookie，不会写入 localStorage 或 sessionStorage。
        </p>
      </div>
      <div className="auth-card">
        <div className="auth-tabs" role="tablist" aria-label="身份操作">
          <button
            id="auth-tab-login"
            ref={loginTab}
            type="button"
            role="tab"
            aria-controls="auth-panel"
            aria-selected={mode === "login"}
            tabIndex={mode === "login" ? 0 : -1}
            onClick={() => selectTab("login", false)}
            onKeyDown={(event) => handleTabKeyDown(event, "login")}
          >
            登录
          </button>
          <button
            id="auth-tab-register"
            ref={registerTab}
            type="button"
            role="tab"
            aria-controls="auth-panel"
            aria-selected={mode === "register"}
            tabIndex={mode === "register" ? 0 : -1}
            onClick={() => selectTab("register", false)}
            onKeyDown={(event) => handleTabKeyDown(event, "register")}
          >
            注册
          </button>
        </div>
        <form
          id="auth-panel"
          role="tabpanel"
          aria-labelledby={`auth-tab-${mode}`}
          onSubmit={(event) => void submit(event)}
        >
          <label className="field">
            <span>用户名</span>
            <input
              required
              minLength={3}
              maxLength={64}
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </label>
          {mode === "register" ? (
            <label className="field">
              <span>邮箱</span>
              <input
                required
                type="email"
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </label>
          ) : null}
          <label className="field">
            <span>密码</span>
            <input
              required
              minLength={8}
              type="password"
              autoComplete={
                mode === "login" ? "current-password" : "new-password"
              }
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {problem ? (
            <p className="inline-problem" role="alert">
              {problem}
            </p>
          ) : null}
          <Button type="submit" disabled={busy}>
            {busy
              ? "正在建立安全会话…"
              : mode === "login"
                ? "登录并继续"
                : "注册并登录"}
          </Button>
        </form>
      </div>
    </section>
  );
}
