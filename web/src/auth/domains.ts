import { apiEnvironment } from "@/api/core/environment";
import { createAuthDomain } from "@/auth/auth-domain";

export const userAuth = createAuthDomain({
  name: "user",
  role: "ROLE_USER",
  baseUrl: apiEnvironment.baseUrl,
  refreshPath: "/api/v1/auth/refresh",
  csrfCookieName: "hotshop_user_csrf",
});

export const adminAuth = createAuthDomain({
  name: "admin",
  role: "ROLE_ADMIN",
  baseUrl: apiEnvironment.baseUrl,
  refreshPath: "/admin/api/v1/auth/refresh",
  csrfCookieName: "hotshop_admin_csrf",
});
