import {
  Activity,
  Boxes,
  ChevronRight,
  ClipboardList,
  Gauge,
  ShieldCheck,
  Users,
  LogOut,
  CalendarClock,
  CircleAlert,
  ScrollText,
  Send,
  Bot,
} from "lucide-react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useStore } from "zustand";

import { Badge } from "@/components/ui/badge";
import type { AuthDomain } from "@/auth/auth-domain";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { logoutUser } from "@/features/auth/logout-user";
import { apiClients } from "@/api/clients";
import { readCookie } from "@/api/core/cookies";

const icons = {
  overview: Gauge,
  orders: ClipboardList,
  catalog: Boxes,
  users: Users,
  activities: CalendarClock,
  exceptions: CircleAlert,
  outbox: Send,
  audit: ScrollText,
  agent: Bot,
};

interface WorkspaceShellProps {
  domain: AuthDomain;
  title: string;
  eyebrow: string;
  tone: "user" | "admin";
  items: Array<{
    label: string;
    to: string;
    icon: keyof typeof icons;
  }>;
}

export function WorkspaceShell({
  domain,
  title,
  eyebrow,
  tone,
  items,
}: WorkspaceShellProps) {
  const session = useStore(domain.store, (state) => state.session);
  const navigate = useNavigate();
  const logout = () => {
    const request =
      tone === "user"
        ? logoutUser()
        : apiClients.admin.authentication
            .logout({ xCSRFToken: readCookie("hotshop_admin_csrf") })
            .finally(() => adminAuthCleanup(domain));
    void request
      .catch(() => undefined)
      .finally(() => {
        void navigate(tone === "admin" ? "/admin/login" : "/");
      });
  };

  return (
    <div className={cn("workspace", `workspace-${tone}`)}>
      <a className="skip-link" href="#workspace-main">
        跳到工作区
      </a>
      <aside className="workspace-sidebar">
        <NavLink className="brand-lockup brand-lockup-inverse" to="/">
          <span className="brand-mark" aria-hidden="true">
            H
          </span>
          <span>
            <strong>HOTSHOP</strong>
            <small>{tone === "admin" ? "OPERATIONS" : "MY HOTSHOP"}</small>
          </span>
        </NavLink>
        <div className="workspace-context">
          <span className="font-utility">{eyebrow}</span>
          <h1>{title}</h1>
        </div>
        <nav className="workspace-nav" aria-label={`${title}导航`}>
          {items.map((item) => {
            const Icon = icons[item.icon];
            return (
              <NavLink
                key={item.to}
                to={item.to}
                aria-label={item.label}
                end={item.to.split("/").length === 2}
                className={({ isActive }) =>
                  cn("workspace-nav-link", isActive && "is-active")
                }
              >
                <Icon aria-hidden="true" />
                <span>{item.label}</span>
                <ChevronRight
                  aria-hidden="true"
                  className="workspace-nav-chevron"
                />
              </NavLink>
            );
          })}
        </nav>
        <div className="workspace-identity">
          <ShieldCheck aria-hidden="true" />
          <div>
            <strong>{session?.username}</strong>
            <span>{tone === "admin" ? session?.role : "我的购物空间"}</span>
          </div>
          {tone === "admin" ? <Badge tone="healthy">内存会话</Badge> : null}
          <Button type="button" variant="ghost" size="sm" onClick={logout}>
            <LogOut aria-hidden="true" />
            退出登录
          </Button>
        </div>
      </aside>
      <div className="workspace-stage">
        <header className="workspace-topbar">
          <div>
            <span className="font-utility">
              {tone === "admin" ? "LIVE DOMAIN" : "MY HOTSHOP"}
            </span>
            <strong>{tone === "admin" ? "ADMIN" : "我的购物空间"}</strong>
          </div>
          <div className="workspace-topbar-actions">
            <div className="live-indicator">
              <Activity aria-hidden="true" />
              <span>{tone === "admin" ? "会话已隔离" : "已登录"}</span>
            </div>
            <Button
              className="workspace-mobile-logout"
              type="button"
              variant="ghost"
              size="sm"
              onClick={logout}
            >
              <LogOut aria-hidden="true" />
              退出登录
            </Button>
          </div>
        </header>
        <main id="workspace-main" tabIndex={-1} className="workspace-main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function adminAuthCleanup(domain: AuthDomain) {
  domain.store.getState().clearSession();
}
