import { describe, expect, it, vi } from "vitest";
import { createAuthDomain } from "@/auth/auth-domain";
import type { AccessSession } from "@/auth/auth-store";
const session = (userId: string): AccessSession => ({
  userId,
  username: userId,
  role: "ROLE_USER",
  accessToken: `token-${userId}`,
  expiresAt: "2030-01-01T00:00:00Z",
});
const create = (fetcher: typeof fetch) =>
  createAuthDomain(
    {
      name: "user",
      role: "ROLE_USER",
      baseUrl: "",
      refreshPath: "/refresh",
      csrfCookieName: "csrf",
    },
    fetcher,
  );

describe("authentication request lifecycle", () => {
  it("cannot restore a session from a refresh that finishes after logout", async () => {
    let resolve: ((response: Response) => void) | undefined;
    const domain = create(
      vi.fn(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      ),
    );
    domain.store.getState().setSession(session("A"));
    const refresh = domain.refresh();
    const rejected = expect(refresh).rejects.toThrow("session expired");
    domain.store.getState().clearSession();
    resolve?.(Response.json(session("A")));
    await rejected;
    expect(domain.store.getState().session).toBeNull();
  });
  it("a failed old refresh cannot clear the newly logged-in account", async () => {
    let resolve: ((response: Response) => void) | undefined;
    const domain = create(
      vi.fn(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      ),
    );
    domain.store.getState().setSession(session("A"));
    const refresh = domain.refresh();
    const rejected = expect(refresh).rejects.toThrow();
    domain.store.getState().setSession(session("B"));
    resolve?.(Response.json({}, { status: 401 }));
    await rejected;
    expect(domain.store.getState().session?.userId).toBe("B");
  });
  it("does not refresh or replay an old request with another account's credentials", async () => {
    let resolve: ((response: Response) => void) | undefined;
    const fetcher = vi.fn(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    const domain = create(fetcher);
    domain.store.getState().setSession(session("A"));
    const request = domain.fetch("/orders");
    const rejected = expect(request).rejects.toThrow("session expired");
    domain.store.getState().setSession(session("B"));
    resolve?.(Response.json({}, { status: 401 }));
    await rejected;
    expect(fetcher).toHaveBeenCalledOnce();
    expect(domain.store.getState().session?.userId).toBe("B");
  });
});
