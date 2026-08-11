import { lazy, Suspense } from "react";
import { BrowserRouter, Route, Routes } from "react-router-dom";

import { ProtectedRoute } from "@/app/protected-route";
import { adminAuth, userAuth } from "@/auth/domains";
import { LoadingState } from "@/components/async-states";
import { PublicShell } from "@/layouts/public-shell";
import { WorkspaceShell } from "@/layouts/workspace-shell";

const AnonymousHome = lazy(() =>
  import("@/pages/anonymous-home").then((module) => ({
    default: module.AnonymousHome,
  })),
);
const UserDashboard = lazy(() =>
  import("@/pages/user-dashboard").then((module) => ({
    default: module.UserDashboard,
  })),
);
const UserAuthPage = lazy(() =>
  import("@/pages/user-auth-page").then((module) => ({
    default: module.UserAuthPage,
  })),
);
const ProductDetailPage = lazy(() =>
  import("@/pages/product-detail-page").then((module) => ({
    default: module.ProductDetailPage,
  })),
);
const OrderListPage = lazy(() =>
  import("@/pages/order-list-page").then((module) => ({
    default: module.OrderListPage,
  })),
);
const OrderDetailPage = lazy(() =>
  import("@/pages/order-detail-page").then((module) => ({
    default: module.OrderDetailPage,
  })),
);
const ReservationDetailPage = lazy(() =>
  import("@/pages/reservation-detail-page").then((module) => ({
    default: module.ReservationDetailPage,
  })),
);
const AdminDashboard = lazy(() =>
  import("@/pages/admin-dashboard").then((module) => ({
    default: module.AdminDashboard,
  })),
);
const AdminLoginPage = lazy(() =>
  import("@/pages/admin-login-page").then((module) => ({
    default: module.AdminLoginPage,
  })),
);
const AdminProductsPage = lazy(() =>
  import("@/pages/admin-products-page").then((module) => ({
    default: module.AdminProductsPage,
  })),
);
const AdminActivitiesPage = lazy(() =>
  import("@/pages/admin-activities-page").then((module) => ({
    default: module.AdminActivitiesPage,
  })),
);
const AdminOrdersPage = lazy(() =>
  import("@/pages/admin-orders-page").then((module) => ({
    default: module.AdminOrdersPage,
  })),
);
const AdminExceptionsPage = lazy(() =>
  import("@/pages/admin-exceptions-page").then((module) => ({
    default: module.AdminExceptionsPage,
  })),
);
const AdminOutboxPage = lazy(() =>
  import("@/pages/admin-outbox-page").then((module) => ({
    default: module.AdminOutboxPage,
  })),
);
const AdminAuditPage = lazy(() =>
  import("@/pages/admin-audit-page").then((module) => ({
    default: module.AdminAuditPage,
  })),
);
const ForbiddenPage = lazy(() =>
  import("@/pages/status-pages").then((module) => ({
    default: module.ForbiddenPage,
  })),
);
const SessionExpiredPage = lazy(() =>
  import("@/pages/status-pages").then((module) => ({
    default: module.SessionExpiredPage,
  })),
);
const NotFoundPage = lazy(() =>
  import("@/pages/status-pages").then((module) => ({
    default: module.NotFoundPage,
  })),
);

export function AppRouter() {
  return (
    <BrowserRouter>
      <Suspense
        fallback={
          <main className="grid min-h-screen place-items-center bg-[var(--canvas)] p-6">
            <div className="w-full max-w-2xl">
              <LoadingState label="正在装载工作区" />
            </div>
          </main>
        }
      >
        <Routes>
          <Route element={<PublicShell />}>
            <Route index element={<AnonymousHome />} />
            <Route path="auth" element={<UserAuthPage />} />
            <Route path="products/:productId" element={<ProductDetailPage />} />
          </Route>

          <Route element={<ProtectedRoute domain={userAuth} />}>
            <Route
              path="/user"
              element={
                <WorkspaceShell
                  domain={userAuth}
                  title="User 工作台"
                  eyebrow="PERSONAL COMMERCE"
                  tone="user"
                  items={[
                    { label: "总览", to: "/user", icon: "overview" },
                    { label: "我的订单", to: "/user/orders", icon: "orders" },
                  ]}
                />
              }
            >
              <Route index element={<UserDashboard />} />
              <Route path="orders" element={<OrderListPage />} />
              <Route path="orders/:orderId" element={<OrderDetailPage />} />
              <Route
                path="reservations/:activityId/:reservationNo"
                element={<ReservationDetailPage />}
              />
            </Route>
          </Route>

          <Route path="/admin/login" element={<AdminLoginPage />} />

          <Route
            element={
              <ProtectedRoute domain={adminAuth} redirectTo="/admin/login" />
            }
          >
            <Route
              path="/admin"
              element={
                <WorkspaceShell
                  domain={adminAuth}
                  title="Administrator"
                  eyebrow="OPERATIONS CONTROL"
                  tone="admin"
                  items={[
                    { label: "交易总览", to: "/admin", icon: "overview" },
                    { label: "商品", to: "/admin/products", icon: "catalog" },
                    {
                      label: "活动",
                      to: "/admin/activities",
                      icon: "activities",
                    },
                    { label: "订单", to: "/admin/orders", icon: "orders" },
                    {
                      label: "异常",
                      to: "/admin/exceptions",
                      icon: "exceptions",
                    },
                    { label: "Outbox", to: "/admin/outbox", icon: "outbox" },
                    { label: "审计", to: "/admin/audit", icon: "audit" },
                  ]}
                />
              }
            >
              <Route index element={<AdminDashboard />} />
              <Route path="products" element={<AdminProductsPage />} />
              <Route path="activities" element={<AdminActivitiesPage />} />
              <Route path="orders" element={<AdminOrdersPage />} />
              <Route path="exceptions" element={<AdminExceptionsPage />} />
              <Route path="outbox" element={<AdminOutboxPage />} />
              <Route path="audit" element={<AdminAuditPage />} />
            </Route>
          </Route>

          <Route path="/forbidden" element={<ForbiddenPage />} />
          <Route path="/session-expired" element={<SessionExpiredPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
