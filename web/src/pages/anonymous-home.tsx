import { ArrowRight, Gauge, ScanLine, ShieldCheck } from "lucide-react";
import { Link } from "react-router-dom";

import { PulseRail } from "@/components/pulse-rail";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

const principles = [
  {
    icon: ScanLine,
    title: "库存先保留",
    description: "抢购入口把资格、库存与事件写入压进一次原子判断。",
  },
  {
    icon: Gauge,
    title: "进度可解释",
    description: "从 Reservation 到 Order，每一次状态推进都有明确语义。",
  },
  {
    icon: ShieldCheck,
    title: "身份不串线",
    description: "User 与 Administrator 使用不同的令牌、刷新链和 API 边界。",
  },
];

export function AnonymousHome() {
  return (
    <>
      <section className="hero-section">
        <div className="hero-copy">
          <Badge tone="signal">FLASH WINDOW · READY</Badge>
          <p className="hero-kicker">把高并发交易，压缩成一条可信路径。</p>
          <h1>
            <span>热度会突发，</span>
            <br />
            <em>交易必须冷静。</em>
          </h1>
          <p className="hero-intro">
            HotShop
            连接商品发现、库存预留、异步成单与支付状态。每个边界都可以被测试、追踪和解释。
          </p>
          <div className="hero-actions">
            <Button asChild size="lg">
              <Link to="/user">
                进入 User 工作台
                <ArrowRight aria-hidden="true" className="size-4" />
              </Link>
            </Button>
            <Button asChild variant="secondary" size="lg">
              <Link to="/admin">查看 Administrator 区</Link>
            </Button>
          </div>
        </div>
        <div className="hero-instrument" aria-label="交易窗口状态">
          <div className="instrument-head">
            <span>FLASH WINDOW / 07</span>
            <Badge tone="healthy">STABLE</Badge>
          </div>
          <div className="instrument-core">
            <span className="font-utility">RESERVATION PULSE</span>
            <strong>
              00:12<span>.840</span>
            </strong>
            <p>窗口只表达当前演示状态，不代表实测性能结论。</p>
          </div>
          <PulseRail />
        </div>
      </section>

      <section className="principle-section" aria-labelledby="principles-title">
        <div className="section-heading">
          <p className="eyebrow">SYSTEM CHARACTER</p>
          <h2 id="principles-title">不是更快地展示，而是更稳地完成。</h2>
        </div>
        <div className="principle-grid">
          {principles.map(({ icon: Icon, title, description }) => (
            <article key={title} className="principle-card">
              <Icon aria-hidden="true" />
              <h3>{title}</h3>
              <p>{description}</p>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}
