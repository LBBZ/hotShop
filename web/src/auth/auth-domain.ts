import { readCookie } from "@/api/core/cookies";
import { toApiUrl } from "@/api/core/environment";
import { mapProblemResponse } from "@/api/core/problem";
import { withRequestId } from "@/api/core/request-id";
import {
  createAuthStore,
  type AccessSession,
  type AuthDomainName,
  type AuthRole,
  type AuthStore,
} from "@/auth/auth-store";

export interface AuthDomainConfig {
  name: AuthDomainName;
  role: AuthRole;
  baseUrl: string;
  refreshPath: string;
  csrfCookieName: string;
}

export interface AuthDomain {
  name: AuthDomainName;
  store: AuthStore;
  fetch: typeof fetch;
  refresh: () => Promise<AccessSession>;
  ensureSession: () => Promise<boolean>;
}

export class SessionExpiredError extends Error {
  readonly domain: AuthDomainName;

  constructor(domain: AuthDomainName) {
    super(`${domain} session expired`);
    this.name = "SessionExpiredError";
    this.domain = domain;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function parseAccessSession(
  value: unknown,
  expectedRole: AuthRole,
): AccessSession | null {
  if (!isRecord(value)) {
    return null;
  }

  const accessToken = value.accessToken;
  const expiresAt = value.expiresAt;
  const role = value.role;
  const userId = value.userId;
  const username = value.username;
  if (
    typeof accessToken !== "string" ||
    typeof expiresAt !== "string" ||
    role !== expectedRole ||
    typeof userId !== "string" ||
    typeof username !== "string"
  ) {
    return null;
  }

  return { accessToken, expiresAt, role: expectedRole, userId, username };
}

export function createAuthDomain(
  config: AuthDomainConfig,
  fetcher: typeof fetch = globalThis.fetch,
): AuthDomain {
  const store = createAuthStore();
  let refreshInFlight: Promise<AccessSession> | null = null;
  let identityEpoch = 0;
  let refreshEpoch = -1;
  store.subscribe((state, previous) => {
    if (
      state.session?.userId !== previous.session?.userId ||
      state.session === null
    )
      identityEpoch += 1;
  });

  async function rawFetch(
    input: RequestInfo | URL,
    init: RequestInit = {},
  ): Promise<Response> {
    return fetcher(toApiUrl(config.baseUrl, input), {
      ...init,
      credentials: init.credentials ?? "include",
      headers: withRequestId(init.headers),
    });
  }

  async function performRefresh(epoch: number): Promise<AccessSession> {
    // A page may log out or change account while waiting for another tab.
    if (epoch !== identityEpoch) throw new SessionExpiredError(config.name);
    const csrfToken = readCookie(config.csrfCookieName);
    const headers = new Headers();
    if (csrfToken) {
      headers.set("X-CSRF-Token", csrfToken);
    }

    const response = await rawFetch(config.refreshPath, {
      method: "POST",
      headers,
      signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) {
      throw await mapProblemResponse(response);
    }

    const session = parseAccessSession(await response.json(), config.role);
    if (epoch !== identityEpoch) throw new SessionExpiredError(config.name);
    if (!session) {
      throw new Error(
        `The ${config.name} refresh response did not match its identity domain.`,
      );
    }
    store.getState().setSession(session);
    return session;
  }

  async function coordinatedRefresh(epoch: number): Promise<AccessSession> {
    // Cookies are shared by tabs, while access tokens intentionally stay in each
    // page's memory. Read the current CSRF cookie only after acquiring the lock.
    if (typeof navigator !== "undefined" && navigator.locks) {
      const resource = `hotshop:refresh:${config.name}:${toApiUrl(config.baseUrl, config.refreshPath)}`;
      return navigator.locks.request(
        resource,
        { signal: AbortSignal.timeout(20_000) },
        () => performRefresh(epoch),
      );
    }
    return performRefresh(epoch);
  }

  function refresh(): Promise<AccessSession> {
    if (!refreshInFlight || refreshEpoch !== identityEpoch) {
      const epoch = identityEpoch;
      refreshEpoch = epoch;
      const pending = coordinatedRefresh(epoch)
        .catch((error: unknown) => {
          if (epoch === identityEpoch) store.getState().clearSession("expired");
          throw error;
        })
        .finally(() => {
          if (refreshInFlight === pending) refreshInFlight = null;
        });
      refreshInFlight = pending;
    }
    return refreshInFlight;
  }

  async function authenticatedFetch(
    input: RequestInfo | URL,
    init: RequestInit = {},
  ): Promise<Response> {
    const epoch = identityEpoch;
    const initialUserId = store.getState().session?.userId;
    const initialHeaders = withRequestId(init.headers);
    const initialToken = store.getState().session?.accessToken;
    if (initialToken) {
      initialHeaders.set("Authorization", `Bearer ${initialToken}`);
    }

    const response = await rawFetch(input, {
      ...init,
      headers: initialHeaders,
    });
    init.signal?.throwIfAborted();
    if (epoch !== identityEpoch) throw new SessionExpiredError(config.name);
    if (response.ok) {
      return response;
    }

    const url = toApiUrl(config.baseUrl, input);
    const isRefreshRequest = url.endsWith(config.refreshPath);
    if (response.status !== 401 || isRefreshRequest) {
      throw await mapProblemResponse(response);
    }

    try {
      if (store.getState().session?.accessToken === initialToken)
        await refresh();
    } catch {
      throw new SessionExpiredError(config.name);
    }

    init.signal?.throwIfAborted();
    if (initialUserId && initialUserId !== store.getState().session?.userId)
      throw new SessionExpiredError(config.name);
    const replayEpoch = identityEpoch;
    const replayHeaders = withRequestId(initialHeaders);
    const refreshedToken = store.getState().session?.accessToken;
    if (refreshedToken) {
      replayHeaders.set("Authorization", `Bearer ${refreshedToken}`);
    }
    const replay = await rawFetch(input, { ...init, headers: replayHeaders });
    if (replayEpoch !== identityEpoch)
      throw new SessionExpiredError(config.name);
    if (!replay.ok) {
      if (replay.status === 401) {
        store.getState().clearSession("expired");
      }
      throw await mapProblemResponse(replay);
    }
    return replay;
  }

  async function ensureSession(): Promise<boolean> {
    if (store.getState().session) {
      return true;
    }
    try {
      await refresh();
      return true;
    } catch {
      return false;
    }
  }

  return {
    name: config.name,
    store,
    fetch: authenticatedFetch,
    refresh,
    ensureSession,
  };
}
