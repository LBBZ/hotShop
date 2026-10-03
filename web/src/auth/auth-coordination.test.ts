import { afterEach, describe, expect, it, vi } from "vitest";
import { createAuthDomain } from "@/auth/auth-domain";

const config = {
  name: "user" as const,
  role: "ROLE_USER" as const,
  baseUrl: "",
  refreshPath: "/api/v1/auth/refresh",
  csrfCookieName: "coordination_csrf",
};
const session = {
  userId: "101",
  username: "reader",
  role: "ROLE_USER",
  accessToken: "in-memory-token",
  expiresAt: "2030-01-01T00:00:00Z",
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("refresh across browser contexts", () => {
  it("reads the successor CSRF cookie after another page has rotated", async () => {
    let queue: Promise<unknown> = Promise.resolve();
    vi.stubGlobal("navigator", {
      locks: {
        request: (
          _name: string,
          _options: unknown,
          action: () => Promise<unknown>,
        ) => {
          const pending = queue.then(action);
          queue = pending.catch(() => undefined);
          return pending;
        },
      },
    });
    document.cookie = "coordination_csrf=first; Path=/";
    const cookies: Array<string | null> = [];
    const fetcher = vi.fn(
      async (_input: RequestInfo | URL, init?: RequestInit) => {
        cookies.push(new Headers(init?.headers).get("X-CSRF-Token"));
        await Promise.resolve();
        document.cookie = "coordination_csrf=successor; Path=/";
        return Response.json(session);
      },
    );
    const first = createAuthDomain(config, fetcher);
    const second = createAuthDomain(config, fetcher);
    await Promise.all([first.refresh(), second.refresh()]);
    expect(cookies).toEqual(["first", "successor"]);
    expect(first.store.getState().session?.userId).toBe("101");
    expect(second.store.getState().session?.userId).toBe("101");
  });

  it("does not rotate after logout while waiting for the browser lock", async () => {
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal("navigator", {
      locks: {
        request: (
          _name: string,
          _options: unknown,
          action: () => Promise<unknown>,
        ) => gate.then(action),
      },
    });
    const fetcher = vi.fn(() => Promise.resolve(Response.json(session)));
    const domain = createAuthDomain(config, fetcher);
    const pending = domain.refresh();
    const rejected = expect(pending).rejects.toThrow("session expired");
    domain.store.getState().clearSession();
    release?.();
    await rejected;
    expect(fetcher).not.toHaveBeenCalled();
  });
});
