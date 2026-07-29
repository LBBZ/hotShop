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
const AdminDashboard = lazy(() =>
  import("@/pages/admin-dashboard").then((module) => ({
    default: module.AdminDashboard,
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
              <Route path="orders" element={<UserDashboard />} />
            </Route>
          </Route>

          <Route element={<ProtectedRoute domain={adminAuth} />}>
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
                    { label: "商品", to: "/admin/catalog", icon: "catalog" },
                    { label: "订单", to: "/admin/orders", icon: "orders" },
                    { label: "Users", to: "/admin/users", icon: "users" },
                  ]}
                />
              }
            >
              <Route index element={<AdminDashboard />} />
              <Route path="catalog" element={<AdminDashboard />} />
              <Route path="orders" element={<AdminDashboard />} />
              <Route path="users" element={<AdminDashboard />} />
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
