import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";

import { adminAuth, userAuth } from "@/auth/domains";
import { bindIdentityQueryCache } from "@/auth/query-scope";

import { AppErrorBoundary } from "@/components/app-error-boundary";

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            retry: (failureCount, error) =>
              error instanceof TypeError && failureCount < 2,
            refetchOnWindowFocus: false,
          },
          mutations: {
            retry: false,
          },
        },
      }),
  );

  useEffect(() => {
    const stopUser = bindIdentityQueryCache(queryClient, userAuth);
    const stopAdmin = bindIdentityQueryCache(queryClient, adminAuth);
    return () => {
      stopUser();
      stopAdmin();
    };
  }, [queryClient]);

  return (
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </AppErrorBoundary>
  );
}
