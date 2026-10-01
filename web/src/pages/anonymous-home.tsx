import {
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  Check,
  Sparkles,
} from "lucide-react";
import { Link } from "react-router-dom";

import { ShoppingSculpture } from "@/components/shopping-sculpture";
import { Button } from "@/components/ui/button";
import { ActivityBoard } from "@/features/catalog/activity-board";
import { ProductCatalog } from "@/features/catalog/product-catalog";

const shoppingNotes = [
  {
    title: "喜欢，再下单。",
    description: "价格、库存和商品详情，购买前看清楚。",
  },
  {
    title: "好机会，有迹可循。",
    description: "活动时间与预约进度，随时都能查看。",
  },
  {
    title: "拿不定主意？聊聊。",
    description: "让 AI 帮你查商品、做对比，决定权始终在你。",
  },
];

export function AnonymousHome() {
  return (
    <>
      <section className="discovery-hero" aria-labelledby="discovery-title">
        <div className="discovery-copy">
          <p className="store-eyebrow">
            <span className="little-spark" aria-hidden="true">
              ✳
            </span>{" "}
            A LITTLE FIND. A BETTER DAY.
          </p>
          <h1 id="discovery-title">
            <span>GOOD FINDS.</span>
            <span>
              GREAT <em>DAYS.</em>
            </span>
          </h1>
          <h2>把心动，装进日常。</h2>
          <p className="discovery-intro">
            发现值得带回家的好物，也给生活一点新鲜感。
            <br className="desktop-break" />
            慢慢挑，轻松选，喜欢就行动。
          </p>
          <div className="discovery-actions">
            <Button asChild size="lg" variant="dark">
              <a href="#catalog">
                逛逛好物
                <ArrowUpRight aria-hidden="true" />
              </a>
            </Button>
            <a className="text-action" href="#drops">
              看看限时活动
              <ArrowRight aria-hidden="true" />
            </a>
          </div>
          <p className="hero-footnote">
            <Sparkles aria-hidden="true" />
            有选择困难？让 AI 陪你挑。
            <Link to="/user/agent" aria-label="打开 AI 购物助手">
              <ArrowUpRight aria-hidden="true" />
            </Link>
          </p>
        </div>
        <div className="discovery-art">
          <span className="art-caption">THE JOY OF FINDING</span>
          <ShoppingSculpture />
          <span className="art-note">一点心动。很多可能。</span>
        </div>
        <a className="scroll-cue" href="#catalog">
          <ArrowDown aria-hidden="true" />
          <span>往下逛逛</span>
        </a>
      </section>
      <div className="discovery-strip" aria-label="HotShop 购物方式">
        <span>为喜欢的生活，挑一点喜欢的。</span>
        <span className="strip-flower" aria-hidden="true">
          ✳
        </span>
        <span>FIND YOUR EVERYDAY EXTRA.</span>
        <span className="strip-flower" aria-hidden="true">
          ✳
        </span>
        <span>好物 · 好价 · 好心情</span>
      </div>
      <ProductCatalog />
      <ActivityBoard />
      <section
        className="shopping-assistant"
        aria-labelledby="assistant-intro-title"
      >
        <div className="assistant-intro">
          <p className="store-eyebrow">
            <Sparkles aria-hidden="true" /> YOUR SHOPPING SIDEKICK
          </p>
          <h2 id="assistant-intro-title">
            一点灵感，
            <br />
            交给你的购物搭子。
          </h2>
          <p>
            说说你想找什么。查价格、比商品、了解活动规则，
            <br className="desktop-break" />让 AI 帮你理清选择，再由你决定。
          </p>
          <Button asChild variant="dark" size="lg">
            <Link to="/user/agent">
              和 AI 聊聊
              <ArrowUpRight aria-hidden="true" />
            </Link>
          </Button>
        </div>
        <div
          className="assistant-preview"
          aria-label="购物助手可以帮助你做什么"
        >
          <div className="assistant-preview-label">
            <span className="assistant-avatar">
              <Sparkles aria-hidden="true" />
            </span>
            <span>
              HotShop AI<small>灵感从一个问题开始</small>
            </span>
          </div>
          <div className="question-bubble">
            想找一件适合自己的好物，
            <br />
            从哪里开始？<span aria-hidden="true">↗</span>
          </div>
          <div className="assistant-capabilities">
            <span>
              <Check aria-hidden="true" />
              查商品与实时价格
            </span>
            <span>
              <Check aria-hidden="true" />
              对比心仪的商品
            </span>
            <span>
              <Check aria-hidden="true" />
              整理购买清单，等你确认
            </span>
          </div>
          <span className="assistant-preview-note">你来提问，你来决定。</span>
        </div>
      </section>
      <section className="shopping-notes" aria-label="购物时可以期待什么">
        {shoppingNotes.map((note) => (
          <article key={note.title}>
            <span className="note-dot" aria-hidden="true" />
            <h3>{note.title}</h3>
            <p>{note.description}</p>
          </article>
        ))}
      </section>
    </>
  );
}
