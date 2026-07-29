import { describe, expect, it, vi } from "vitest";

import {
  createAuthDomain,
  SessionExpiredError,
  type AuthDomainConfig,
} from "@/auth/auth-domain";

const userConfig: AuthDomainConfig = {
  name: "user",
  role: "ROLE_USER",
  baseUrl: "",
  refreshPath: "/api/v1/auth/refresh",
  csrfCookieName: "hotshop_user_csrf",
};

const adminConfig: AuthDomainConfig = {
  name: "admin",
  role: "ROLE_ADMIN",
  baseUrl: "",
  refreshPath: "/admin/api/v1/auth/refresh",
  csrfCookieName: "hotshop_admin_csrf",
};

function tokenResponse(
  role: "ROLE_USER" | "ROLE_ADMIN",
  accessToken: string,
): Response {
  return Response.json({
    accessToken,
    expiresAt: "2030-01-01T00:00:00Z",
    role,
    userId: role === "ROLE_USER" ? "101" : "901",
    username: role === "ROLE_USER" ? "lin" : "operator",
  });
}

function problemResponse(status = 401): Response {
  return Response.json(
    {
      type: "https://hotshop.local/problems/authentication-required",
      title: "Authentication required",
      status,
      detail: "The session cannot be refreshed",
      instance: "/api/v1/auth/refresh",
      code: "AUTHENTICATION_REQUIRED",
      requestId: "request-401",
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
    },
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") {
    return input;
  }
  return input instanceof URL ? input.toString() : input.url;
}

describe("authentication identity domains", () => {
  it("runs one refresh for concurrent 401 responses and replays every request", async () => {
    let refreshCalls = 0;
    let orderCalls = 0;
    let releaseRefresh: (() => void) | undefined;
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });

    const fetcher = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = requestUrl(input);
        if (url.endsWith(userConfig.refreshPath)) {
          refreshCalls += 1;
          await refreshGate;
          return tokenResponse("ROLE_USER", "user-fresh");
        }

        orderCalls += 1;
        const authorization = new Headers(init?.headers).get("Authorization");
        return authorization === "Bearer user-fresh"
          ? Response.json({ items: [], hasMore: false })
          : problemResponse();
      },
    ) as unknown as typeof fetch;

    const domain = createAuthDomain(userConfig, fetcher);
    domain.store.getState().setSession({
      accessToken: "user-stale",
      expiresAt: "2020-01-01T00:00:00Z",
      role: "ROLE_USER",
      userId: "101",
      username: "lin",
    });

    const requests = [
      domain.fetch("/api/v1/orders"),
      domain.fetch("/api/v1/orders"),
      domain.fetch("/api/v1/orders"),
    ];
    await vi.waitFor(() => {
      expect(refreshCalls).toBe(1);
    });
    releaseRefresh?.();
    const responses = await Promise.all(requests);

    expect(responses.every((response) => response.ok)).toBe(true);
    expect(refreshCalls).toBe(1);
    expect(orderCalls).toBe(6);
    expect(domain.store.getState().session?.accessToken).toBe("user-fresh");
  });

  it("clears the complete session atomically when refresh fails", async () => {
    const fetcher = vi.fn((input: RequestInfo | URL) =>
      Promise.resolve(
        requestUrl(input).endsWith(userConfig.refreshPath)
          ? problemResponse()
          : problemResponse(),
      ),
    ) as unknown as typeof fetch;
    const domain = createAuthDomain(userConfig, fetcher);
    domain.store.getState().setSession({
      accessToken: "user-stale",
      expiresAt: "2020-01-01T00:00:00Z",
      role: "ROLE_USER",
      userId: "101",
      username: "lin",
    });

    await expect(domain.fetch("/api/v1/orders")).rejects.toBeInstanceOf(
      SessionExpiredError,
    );
    expect(domain.store.getState()).toMatchObject({
      status: "expired",
      session: null,
    });
  });

  it("clears the session when refresh cannot reach the API", async () => {
    const fetcher = vi.fn(() =>
      Promise.reject(new TypeError("network unavailable")),
    ) as unknown as typeof fetch;
    const domain = createAuthDomain(userConfig, fetcher);
    domain.store.getState().setSession({
      accessToken: "user-stale",
      expiresAt: "2020-01-01T00:00:00Z",
      role: "ROLE_USER",
      userId: "101",
      username: "lin",
    });

    await expect(domain.refresh()).rejects.toThrow("network unavailable");
    expect(domain.store.getState()).toMatchObject({
      status: "expired",
      session: null,
    });
  });

  it("keeps User and Administrator refresh, tokens, and headers isolated", async () => {
    document.cookie = "hotshop_user_csrf=user-csrf; Path=/";
    document.cookie = "hotshop_admin_csrf=admin-csrf; Path=/";
    const seen = new Map<string, Headers>();
    const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = requestUrl(input);
      seen.set(url, new Headers(init?.headers));
      if (url === userConfig.refreshPath) {
        return Promise.resolve(tokenResponse("ROLE_USER", "only-user-token"));
      }
      if (url === adminConfig.refreshPath) {
        return Promise.resolve(tokenResponse("ROLE_ADMIN", "only-admin-token"));
      }
      return Promise.resolve(Response.json({ ok: true }));
    }) as unknown as typeof fetch;

    const user = createAuthDomain(userConfig, fetcher);
    const admin = createAuthDomain(adminConfig, fetcher);
    await Promise.all([user.refresh(), admin.refresh()]);
    await user.fetch("/api/v1/users/me");
    await admin.fetch("/admin/api/v1/users");

    expect(user.store.getState().session).toMatchObject({
      role: "ROLE_USER",
      accessToken: "only-user-token",
    });
    expect(admin.store.getState().session).toMatchObject({
      role: "ROLE_ADMIN",
      accessToken: "only-admin-token",
    });
    expect(seen.get("/api/v1/auth/refresh")?.get("X-CSRF-Token")).toBe(
      "user-csrf",
    );
    expect(seen.get("/admin/api/v1/auth/refresh")?.get("X-CSRF-Token")).toBe(
      "admin-csrf",
    );
    expect(seen.get("/api/v1/users/me")?.get("Authorization")).toBe(
      "Bearer only-user-token",
    );
    expect(seen.get("/admin/api/v1/users")?.get("Authorization")).toBe(
      "Bearer only-admin-token",
    );
  });

  it("rejects a token response from the wrong identity domain", async () => {
    const fetcher = vi.fn(() =>
      Promise.resolve(tokenResponse("ROLE_USER", "wrong-audience-shape")),
    ) as unknown as typeof fetch;
    const admin = createAuthDomain(adminConfig, fetcher);

    await expect(admin.refresh()).rejects.toThrow("identity domain");
    expect(admin.store.getState().session).toBeNull();
  });
});
