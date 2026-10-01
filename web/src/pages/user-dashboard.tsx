import {
  ArrowUpRight,
  PackageCheck,
  ShoppingBag,
  Sparkles,
} from "lucide-react";
import { Link } from "react-router-dom";
import { useStore } from "zustand";

import { Button } from "@/components/ui/button";
import { userAuth } from "@/auth/domains";

export function UserDashboard() {
  const session = useStore(userAuth.store, (state) => state.session);

  return (
    <div className="dashboard-stack">
      <header className="dashboard-heading">
        <div>
          <p className="eyebrow">YOUR EVERYDAY FINDS</p>
          <h2>你好，{session?.username}</h2>
          <p>好物慢慢挑，喜欢的日常由你决定。</p>
        </div>
        <span className="user-welcome-note">
          <Sparkles aria-hidden="true" />
          很高兴，又见到你。
        </span>
      </header>

      <section className="user-discovery-panel" aria-labelledby="focus-title">
        <div>
          <p className="eyebrow">A LITTLE FIND. A BETTER DAY.</p>
          <h3 id="focus-title">
            把喜欢的日常，
            <br />
            带回家。
          </h3>
          <p>看看值得入手的好物，也许下一件心动就在这里。</p>
          <div className="user-discovery-actions">
            <Button asChild variant="dark">
              <Link to="/">
                发现好物
                <ArrowUpRight aria-hidden="true" />
              </Link>
            </Button>
            <Link className="user-text-link" to="/#drops">
              看看限时活动
              <ArrowUpRight aria-hidden="true" />
            </Link>
          </div>
        </div>
        <div className="user-bag-mark" aria-hidden="true">
          <ShoppingBag strokeWidth={0.9} />
          <span>h.</span>
          <i>✳</i>
        </div>
      </section>

      <div className="user-shortcuts">
        <article className="user-shortcut-card">
          <span className="user-shortcut-icon">
            <PackageCheck aria-hidden="true" />
          </span>
          <p className="eyebrow">YOUR ORDERS</p>
          <h3>惦记的好物，进展到哪了？</h3>
          <p>查看自己的订单，继续付款，或了解最新进度。</p>
          <Link className="user-text-link" to="/user/orders">
            查看我的订单
            <ArrowUpRight aria-hidden="true" />
          </Link>
        </article>
        <article className="user-shortcut-card user-shortcut-ai">
          <span className="user-shortcut-icon">
            <Sparkles aria-hidden="true" />
          </span>
          <p className="eyebrow">YOUR SHOPPING SIDEKICK</p>
          <h3>拿不定主意？一起聊聊。</h3>
          <p>找商品、比价格、查订单，让购物搭子帮你理清选择。</p>
          <Link className="user-text-link" to="/user/agent">
            和 AI 聊聊
            <ArrowUpRight aria-hidden="true" />
          </Link>
        </article>
      </div>

      <section className="user-shopping-steps" aria-label="购物流程">
        <p>从喜欢，到拥有。</p>
        <ol>
          <li>
            <span>01</span>挑选好物
          </li>
          <li>
            <span>02</span>确认下单
          </li>
          <li>
            <span>03</span>完成付款
          </li>
          <li>
            <span>04</span>查看进度
          </li>
        </ol>
      </section>
    </div>
  );
}
