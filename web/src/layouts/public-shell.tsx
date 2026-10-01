import { ArrowUp, ArrowUpRight, Menu, Sparkles, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { useStore } from "zustand";

import { Button } from "@/components/ui/button";
import { userAuth } from "@/auth/domains";

export function PublicShell() {
  const session = useStore(userAuth.store, (state) => state.session);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const location = useLocation();
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      if (location.hash)
        document.getElementById(location.hash.slice(1))?.scrollIntoView();
      else window.scrollTo({ top: 0, behavior: "instant" });
    });
    return () => cancelAnimationFrame(frame);
  }, [location.pathname, location.hash]);
  return (
    <div className="storefront" id="page-top">
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <div className="store-announcement">
        <span>生活的热爱，从一件好物开始。</span>
        <span>
          GOOD THINGS, FOUND HERE.
          <Sparkles aria-hidden="true" />
        </span>
      </div>
      <header
        className="store-header"
        onKeyDown={(event) => {
          if (event.key === "Escape" && menuOpen) {
            setMenuOpen(false);
            menuButton.current?.focus();
          }
        }}
      >
        <div className="store-header-inner">
          <Link
            className="store-brand"
            to="/"
            aria-label="HotShop 首页"
            onClick={() => setMenuOpen(false)}
          >
            <span className="store-brand-icon" aria-hidden="true">
              h<span>✳</span>
            </span>
            <strong>hotshop</strong>
          </Link>
          <nav
            id="store-navigation"
            aria-label="主要导航"
            className={`store-navigation${menuOpen ? "is-open" : ""}`}
            onClick={() => setMenuOpen(false)}
          >
            <NavLink to="/" end>
              发现好物
            </NavLink>
            <Link to="/#drops">限时活动</Link>
            <Link to="/user/agent">
              AI 帮我选
              <Sparkles aria-hidden="true" />
            </Link>
            <Link to="/user/orders">我的订单</Link>
          </nav>
          <div className="store-header-actions">
            <Button asChild variant="dark" size="sm">
              <Link to={session ? "/user" : "/auth"}>
                {session ? `你好，${session.username}` : "登录 / 注册"}
                <ArrowUpRight aria-hidden="true" />
              </Link>
            </Button>
            <button
              ref={menuButton}
              type="button"
              className="store-menu-toggle"
              aria-label={menuOpen ? "关闭导航" : "打开导航"}
              aria-expanded={menuOpen}
              aria-controls="store-navigation"
              onClick={() => setMenuOpen(!menuOpen)}
            >
              {menuOpen ? (
                <X aria-hidden="true" />
              ) : (
                <Menu aria-hidden="true" />
              )}
            </button>
          </div>
        </div>
      </header>
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="store-footer">
        <div className="store-footer-top">
          <div>
            <p className="store-eyebrow">A LITTLE FIND. A BETTER DAY.</p>
            <h2>
              下一个心动，
              <br className="mobile-break" />
              就在日常里。
            </h2>
          </div>
          <a className="back-to-top" href="#page-top" aria-label="回到顶部">
            <ArrowUp aria-hidden="true" />
          </a>
        </div>
        <div className="footer-wordmark" aria-hidden="true">
          hotshop<span>✳</span>
        </div>
        <div className="store-footer-bottom">
          <p>
            © {new Date().getFullYear()} HotShop
            <span>发现好物，热爱日常。</span>
          </p>
          <nav aria-label="页脚导航">
            <Link to="/#catalog">商品目录</Link>
            <Link to="/user/agent">购物助手</Link>
            <Link to="/admin">
              运营入口
              <ArrowUpRight aria-hidden="true" />
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
