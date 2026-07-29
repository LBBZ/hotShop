import { useEffect, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useStore } from "zustand";

import { LoadingState } from "@/components/async-states";
import type { AuthDomain } from "@/auth/auth-domain";

export function ProtectedRoute({ domain }: { domain: AuthDomain }) {
  const session = useStore(domain.store, (state) => state.session);
  const [checked, setChecked] = useState(Boolean(session));
  const location = useLocation();

  useEffect(() => {
    let active = true;
    if (session) {
      setChecked(true);
      return;
    }

    void domain.ensureSession().finally(() => {
      if (active) {
        setChecked(true);
      }
    });

    return () => {
      active = false;
    };
  }, [domain, session]);

  if (!checked) {
    return (
      <main className="grid min-h-screen place-items-center bg-[var(--canvas)] p-6">
        <div className="w-full max-w-2xl">
          <LoadingState label={`正在恢复 ${domain.name} 会话`} />
        </div>
      </main>
    );
  }

  if (!session) {
    return (
      <Navigate
        to={`/session-expired?domain=${domain.name}`}
        replace
        state={{ from: location.pathname }}
      />
    );
  }

  return <Outlet />;
}
