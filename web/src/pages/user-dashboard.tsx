import { ArrowUpRight, Clock3, PackageCheck, ShoppingBag } from "lucide-react";
import { Link } from "react-router-dom";
import { useStore } from "zustand";

import { EmptyState } from "@/components/async-states";
import { PulseRail } from "@/components/pulse-rail";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { userAuth } from "@/auth/domains";

export function UserDashboard() {
  const session = useStore(userAuth.store, (state) => state.session);

  return (
    <div className="dashboard-stack">
      <header className="dashboard-heading">
        <div>
          <p className="eyebrow">USER WORKBENCH</p>
          <h2>早上好，{session?.username}</h2>
          <p>从预留到支付，只看属于你的交易。</p>
        </div>
        <Badge tone="healthy">Access 仅驻留内存</Badge>
      </header>

      <section className="focus-panel" aria-labelledby="focus-title">
        <div className="focus-copy">
          <span className="focus-icon" aria-hidden="true">
            <PackageCheck />
          </span>
          <p className="eyebrow">CURRENT FLOW</p>
          <h3 id="focus-title">你的下一笔交易，会沿这条路径推进。</h3>
          <p>
            Refresh Cookie 由浏览器处理；页面刷新后，工作台通过 User
            专属边界恢复短期 Access。
          </p>
        </div>
        <PulseRail />
      </section>

      <div className="dashboard-grid">
        <article className="metric-card">
          <Clock3 aria-hidden="true" />
          <span>会话到期时间</span>
          <strong>
            {session
              ? new Intl.DateTimeFormat("zh-CN", {
                  hour: "2-digit",
                  minute: "2-digit",
                }).format(new Date(session.expiresAt))
              : "—"}
          </strong>
          <p>不会写入浏览器持久化存储</p>
        </article>
        <div className="dashboard-empty">
          <EmptyState
            title="从一件真实商品开始"
            description="普通订单和秒杀预约都会写入服务端事实，并通过可恢复状态流推进。"
            action={
              <Button asChild size="sm">
                <Link to="/">
                  <ShoppingBag aria-hidden="true" className="size-3.5" />
                  浏览商品与活动
                  <ArrowUpRight aria-hidden="true" className="size-3.5" />
                </Link>
              </Button>
            }
          />
        </div>
      </div>
    </div>
  );
}
