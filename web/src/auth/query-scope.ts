import type { QueryClient } from "@tanstack/react-query";
import { useStore } from "zustand";

import type { AuthDomain } from "@/auth/auth-domain";
import { userAuth } from "@/auth/domains";

export function privateQueryKey(
  domain: AuthDomain,
  ...resource: readonly string[]
) {
  return [
    "private",
    domain.name,
    domain.store.getState().session?.userId ?? null,
    ...resource,
  ] as const;
}

export function useUserQueryKey(...resource: readonly string[]) {
  const userId = useStore(
    userAuth.store,
    (state) => state.session?.userId ?? null,
  );
  return ["private", "user", userId, ...resource] as const;
}

/** Remove private data synchronously, including cancelled in-flight queries. */
export function bindIdentityQueryCache(
  client: QueryClient,
  domain: AuthDomain,
) {
  return domain.store.subscribe((state, previous) => {
    if (state.session?.userId === previous.session?.userId) return;
    client.removeQueries({
      predicate: (query) =>
        query.queryKey[0] === "private" && query.queryKey[1] === domain.name,
    });
  });
}
