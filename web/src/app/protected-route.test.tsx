import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { ProtectedRoute } from "@/app/protected-route";
import { createAuthDomain, type AuthDomainConfig } from "@/auth/auth-domain";

const config: AuthDomainConfig = {
  name: "user",
  role: "ROLE_USER",
  baseUrl: "",
  refreshPath: "/api/v1/auth/refresh",
  csrfCookieName: "hotshop_user_csrf",
};

function renderGuard(fetcher: typeof fetch) {
  const domain = createAuthDomain(config, fetcher);
  render(
    <MemoryRouter initialEntries={["/protected"]}>
      <Routes>
        <Route element={<ProtectedRoute domain={domain} />}>
          <Route path="/protected" element={<h1>User 工作台</h1>} />
        </Route>
        <Route path="/session-expired" element={<h1>会话已结束</h1>} />
      </Routes>
    </MemoryRouter>,
  );
  return domain;
}

describe("protected route", () => {
  it("restores a session before rendering protected content", async () => {
    const fetcher = vi.fn(() =>
      Promise.resolve(
        Response.json({
          accessToken: "user-access",
          expiresAt: "2030-01-01T00:00:00Z",
          role: "ROLE_USER",
          userId: "101",
          username: "lin",
        }),
      ),
    ) as unknown as typeof fetch;

    const domain = renderGuard(fetcher);

    expect(screen.getByRole("status")).toHaveTextContent("正在恢复 user 会话");
    expect(
      await screen.findByRole("heading", { name: "User 工作台" }),
    ).toBeInTheDocument();
    expect(domain.store.getState().status).toBe("authenticated");
  });

  it("redirects after a failed refresh and leaves no Access Token", async () => {
    const fetcher = vi.fn(() =>
      Promise.resolve(
        Response.json(
          {
            type: "https://hotshop.local/problems/authentication-required",
            title: "Authentication required",
            status: 401,
            detail: "Refresh failed",
            instance: "/api/v1/auth/refresh",
            code: "AUTHENTICATION_REQUIRED",
            requestId: "guard-401",
            traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
          },
          { status: 401 },
        ),
      ),
    ) as unknown as typeof fetch;

    const domain = renderGuard(fetcher);

    expect(
      await screen.findByRole("heading", { name: "会话已结束" }),
    ).toBeInTheDocument();
    expect(domain.store.getState().session).toBeNull();
  });
});
