import { readCookie } from "@/api/core/cookies";
import { userAuth } from "@/auth/domains";

export async function logoutUser(): Promise<void> {
  const csrf = readCookie("hotshop_user_csrf");
  const headers = new Headers();
  if (csrf) headers.set("X-CSRF-Token", csrf);
  try {
    await userAuth.fetch("/api/v1/auth/logout", { method: "POST", headers });
  } finally {
    userAuth.store.getState().clearSession();
  }
}
