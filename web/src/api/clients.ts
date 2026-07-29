import {
  PublicAuthenticationApi,
  PublicProductsApi,
} from "@/api/generated/public";
import { Configuration as PublicConfiguration } from "@/api/generated/public/runtime";
import {
  PublicAuthenticationApi as UserAuthenticationApi,
  UserOrdersApi,
  UserProfileApi,
} from "@/api/generated/user";
import { Configuration as UserConfiguration } from "@/api/generated/user/runtime";
import {
  AdminAuditLogsApi,
  AdminAuthenticationApi,
  AdminOrdersApi,
  AdminProductsApi,
  AdminUsersApi,
} from "@/api/generated/admin";
import { Configuration as AdminConfiguration } from "@/api/generated/admin/runtime";
import { apiEnvironment } from "@/api/core/environment";
import { publicFetch } from "@/api/core/public-fetch";
import { adminAuth, userAuth } from "@/auth/domains";

const publicConfiguration = new PublicConfiguration({
  basePath: apiEnvironment.baseUrl,
  credentials: "include",
  fetchApi: publicFetch,
});

const userConfiguration = new UserConfiguration({
  basePath: apiEnvironment.baseUrl,
  credentials: "include",
  fetchApi: userAuth.fetch,
});

const adminConfiguration = new AdminConfiguration({
  basePath: apiEnvironment.baseUrl,
  credentials: "include",
  fetchApi: adminAuth.fetch,
});

export const apiClients = Object.freeze({
  public: Object.freeze({
    authentication: new PublicAuthenticationApi(publicConfiguration),
    products: new PublicProductsApi(publicConfiguration),
  }),
  user: Object.freeze({
    authentication: new UserAuthenticationApi(userConfiguration),
    orders: new UserOrdersApi(userConfiguration),
    profile: new UserProfileApi(userConfiguration),
  }),
  admin: Object.freeze({
    auditLogs: new AdminAuditLogsApi(adminConfiguration),
    authentication: new AdminAuthenticationApi(adminConfiguration),
    orders: new AdminOrdersApi(adminConfiguration),
    products: new AdminProductsApi(adminConfiguration),
    users: new AdminUsersApi(adminConfiguration),
  }),
});
