import {
  Activity,
  Boxes,
  ChevronRight,
  ClipboardList,
  Gauge,
  ShieldCheck,
  Users,
} from "lucide-react";
import { NavLink, Outlet } from "react-router-dom";
import { useStore } from "zustand";

import { Badge } from "@/components/ui/badge";
import type { AuthDomain } from "@/auth/auth-domain";
import { cn } from "@/lib/utils";

const icons = {
  overview: Gauge,
  orders: ClipboardList,
  catalog: Boxes,
  users: Users,
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
