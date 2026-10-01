import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";

import { createAuthDomain } from "@/auth/auth-domain";
import { bindIdentityQueryCache, privateQueryKey } from "@/auth/query-scope";
import type { AccessSession } from "@/auth/auth-store";

const session = (userId: string): AccessSession => ({
  userId,
  username: userId,
  role: "ROLE_USER",
  accessToken: `token-${userId}`,
  expiresAt: "2030-01-01T00:00:00Z",
});
const auth = () =>
  createAuthDomain({
    name: "user",
    role: "ROLE_USER",
    baseUrl: "",
    refreshPath: "/refresh",
    csrfCookieName: "csrf",
  });

it("never reuses the previous account's fresh orders and retains public/admin caches", async () => {
  const domain = auth();
  const client = new QueryClient({
    defaultOptions: { queries: { staleTime: 30_000, gcTime: Infinity } },
  });
  const stop = bindIdentityQueryCache(client, domain);
  domain.store.getState().setSession(session("A"));
  const aKey = privateQueryKey(domain, "my-orders");
  await client.fetchQuery({
    queryKey: aKey,
    queryFn: () => Promise.resolve(["order-a"]),
  });
  client.setQueryData(["products"], ["public-product"]);
  client.setQueryData(["private", "admin", "901", "orders"], ["admin-order"]);
  domain.store.getState().clearSession();
  expect(client.getQueryData(aKey)).toBeUndefined();
  domain.store.getState().setSession(session("B"));
  const loadB = vi.fn(() => Promise.resolve(["order-b"]));
  expect(
    await client.fetchQuery({
      queryKey: privateQueryKey(domain, "my-orders"),
      queryFn: loadB,
    }),
  ).toEqual(["order-b"]);
  expect(loadB).toHaveBeenCalledOnce();
  expect(client.getQueryData(["products"])).toEqual(["public-product"]);
  expect(client.getQueryData(["private", "admin", "901", "orders"])).toEqual([
    "admin-order",
  ]);
  stop();
  client.clear();
});

describe("identity transitions", () => {
  it("aborts an old request and ignores its late result after direct account switching", async () => {
    const domain = auth();
    const client = new QueryClient({
      defaultOptions: { queries: { gcTime: Infinity, retry: false } },
    });
    const stop = bindIdentityQueryCache(client, domain);
    domain.store.getState().setSession(session("A"));
    let signal: AbortSignal | undefined;
    let finish: ((value: string[]) => void) | undefined;
    const key = privateQueryKey(domain, "my-orders");
    const request = client.fetchQuery({
      queryKey: key,
      queryFn: (context) => {
        signal = context.signal;
        return new Promise<string[]>((resolve) => {
          finish = resolve;
        });
      },
    });
    const rejected = expect(request).rejects.toThrow();
    domain.store.getState().setSession(session("B"));
    expect(signal?.aborted).toBe(true);
    finish?.(["late-a-order"]);
    await rejected;
    expect(client.getQueryData(key)).toBeUndefined();
    stop();
    client.clear();
  });
  it("keeps private cache on an access-token refresh for the same account", () => {
    const domain = auth();
    const client = new QueryClient({
      defaultOptions: { queries: { gcTime: Infinity } },
    });
    const stop = bindIdentityQueryCache(client, domain);
    domain.store.getState().setSession(session("A"));
    const key = privateQueryKey(domain, "my-orders");
    client.setQueryData(key, ["order-a"]);
    domain.store
      .getState()
      .setSession({ ...session("A"), accessToken: "refreshed" });
    expect(client.getQueryData(key)).toEqual(["order-a"]);
    stop();
    client.clear();
  });
});
