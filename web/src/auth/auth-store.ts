import { createStore } from "zustand/vanilla";

export type AuthDomainName = "user" | "admin";
export type AuthStatus = "idle" | "authenticated" | "expired";
export type AuthRole = "ROLE_USER" | "ROLE_ADMIN";

export interface AccessSession {
  accessToken: string;
  expiresAt: string;
  role: AuthRole;
  userId: string;
  username: string;
}

export interface AuthState {
  status: AuthStatus;
  session: AccessSession | null;
  setSession: (session: AccessSession) => void;
  clearSession: (status?: Exclude<AuthStatus, "authenticated">) => void;
}

export function createAuthStore() {
  return createStore<AuthState>()((set) => ({
    status: "idle",
    session: null,
    setSession: (session) => {
      set({ session, status: "authenticated" });
    },
    clearSession: (status = "idle") => {
      set({ session: null, status });
    },
  }));
}

export type AuthStore = ReturnType<typeof createAuthStore>;
