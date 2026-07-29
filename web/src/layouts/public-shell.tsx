import { ArrowUpRight } from "lucide-react";
import { Link, NavLink, Outlet } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function PublicShell() {
  return (
    <div className="min-h-screen bg-[var(--canvas)]">
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <header className="public-header">
        <Link className="brand-lockup" to="/" aria-label="HotShop 首页">
          <span className="brand-mark" aria-hidden="true">
            H
          </span>
          <span>
            <strong>HOTSHOP</strong>
            <small>FLASH COMMERCE</small>
          </span>
        </Link>
        <nav aria-label="主要导航" className="public-nav">
          <NavLink
            to="/"
            end
            className={({ isActive }) =>
              cn("nav-link", isActive && "nav-link-active")
            }
          >
            发现
          </NavLink>
          <NavLink
            to="/user"
            className={({ isActive }) =>
              cn("nav-link", isActive && "nav-link-active")
            }
          >
            User 工作台
          </NavLink>
          <Button asChild variant="dark" size="sm">
            <Link to="/admin">
              Administrator
              <ArrowUpRight aria-hidden="true" className="size-3.5" />
            </Link>
          </Button>
        </nav>
      </header>
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="public-footer">
        <p>HotShop · Agentic High-Concurrency Commerce Platform</p>
        <p>前端权限只改善操作体验；服务端始终执行最终授权。</p>
      </footer>
    </div>
  );
}
