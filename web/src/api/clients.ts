import {
  PublicAuthenticationApi,
  PublicFlashSaleActivitiesApi,
  PublicProductsApi,
} from "@/api/generated/public";
import { Configuration as PublicConfiguration } from "@/api/generated/public/runtime";
import {
  PublicAuthenticationApi as UserAuthenticationApi,
  UserFlashSaleReservationsApi,
  UserMockPaymentsApi,
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

const adminAuthenticationConfiguration = new AdminConfiguration({
  basePath: apiEnvironment.baseUrl,
  credentials: "include",
  fetchApi: (input, init) => {
    const url =
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    return url.endsWith("/admin/api/v1/auth/login")
      ? publicFetch(input, init)
      : adminAuth.fetch(input, init);
  },
});

export const apiClients = Object.freeze({
  public: Object.freeze({
    activities: new PublicFlashSaleActivitiesApi(publicConfiguration),
    authentication: new PublicAuthenticationApi(publicConfiguration),
    products: new PublicProductsApi(publicConfiguration),
  }),
  user: Object.freeze({
    authentication: new UserAuthenticationApi(userConfiguration),
    flashSales: new UserFlashSaleReservationsApi(userConfiguration),
    orders: new UserOrdersApi(userConfiguration),
    payments: new UserMockPaymentsApi(userConfiguration),
    profile: new UserProfileApi(userConfiguration),
  }),
  admin: Object.freeze({
    auditLogs: new AdminAuditLogsApi(adminConfiguration),
    authentication: new AdminAuthenticationApi(
      adminAuthenticationConfiguration,
    ),
    orders: new AdminOrdersApi(adminConfiguration),
    products: new AdminProductsApi(adminConfiguration),
    users: new AdminUsersApi(adminConfiguration),
  }),
});
