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
            <small>{tone === "admin" ? "OPERATIONS" : "USER DESK"}</small>
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
            <span>{session?.role}</span>
          </div>
          <Badge tone="healthy">内存会话</Badge>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              const logout =
                tone === "user"
                  ? logoutUser()
                  : apiClients.admin.authentication
                      .logout({
                        xCSRFToken: readCookie("hotshop_admin_csrf"),
                      })
                      .finally(() => adminAuthCleanup(domain));
              void logout.finally(() => {
                void navigate(tone === "admin" ? "/admin/login" : "/");
              });
            }}
          >
            <LogOut aria-hidden="true" />
            退出登录
          </Button>
        </div>
      </aside>
      <div className="workspace-stage">
        <header className="workspace-topbar">
          <div>
            <span className="font-utility">LIVE DOMAIN</span>
            <strong>{tone === "admin" ? "ADMIN" : "USER"}</strong>
          </div>
          <div className="live-indicator">
            <Activity aria-hidden="true" />
            <span>会话已隔离</span>
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
