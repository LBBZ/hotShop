import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, MessageCircle, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useStore } from "zustand";

import { apiClients } from "@/api/clients";
import { ApiProblemError } from "@/api/core/problem";
import { userAuth } from "@/auth/domains";
import { ErrorState, LoadingState } from "@/components/async-states";
import { ProductGallery } from "@/components/product-gallery";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  clearPurchaseIntent,
  getOrCreatePurchaseIntent,
  purchaseIntentFingerprint,
} from "@/features/transactions/purchase-intent";

import "./product-detail.css";

interface CreatedOrder {
  orderId: string;
  status: string;
  requestId: string;
  idempotencyReplayed: boolean;
}

function isCreatedOrder(value: unknown): value is CreatedOrder {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.orderId === "string" &&
    typeof record.status === "string" &&
    typeof record.requestId === "string" &&
    typeof record.idempotencyReplayed === "boolean"
  );
}

export function ProductDetailPage() {
  const { productId = "" } = useParams();
  const session = useStore(userAuth.store, (state) => state.session);
  const navigate = useNavigate();
  const [quantity, setQuantity] = useState(1);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string>();
  const query = useQuery({
    queryKey: ["product", productId],
    queryFn: () => apiClients.public.products.getProduct({ productId }),
    enabled: Boolean(productId),
  });

  const buy = async () => {
    if (
      busy ||
      !Number.isInteger(quantity) ||
      quantity < 1 ||
      quantity > Math.min(100, query.data?.stock ?? 0)
    )
      return;
    setBusy(true);
    setProblem(undefined);
    try {
      if (!userAuth.store.getState().session) {
        const restored = await userAuth.ensureSession();
        if (!restored) {
          void navigate(
            `/auth?returnTo=${encodeURIComponent(`/products/${productId}`)}`,
          );
          return;
        }
      }
      const storageKey = `hotshop.purchase-intent.order.${productId}`;
      const intent = getOrCreatePurchaseIntent(
        storageKey,
        purchaseIntentFingerprint({ productId, quantity }),
        "order",
      );
      const idempotencyKey = intent.key;
      let attempt = 0;
      while (true) {
        try {
          const response = await userAuth.fetch("/api/v1/orders", {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "Idempotency-Key": idempotencyKey,
            },
            body: JSON.stringify({ items: [{ productId, quantity }] }),
          });
          const value: unknown = await response.json();
          if (!isCreatedOrder(value))
            throw new Error("订单响应不符合约定契约。");
          clearPurchaseIntent(storageKey, idempotencyKey);
          void navigate(`/user/orders/${value.orderId}`, {
            state: { requestId: value.requestId },
          });
          return;
        } catch (error) {
          const recoverable =
            error instanceof ApiProblemError &&
            (error.problem.status === 429 || error.problem.status >= 500);
          if (!recoverable || attempt >= 2) throw error;
          attempt += 1;
          await new Promise((resolve) =>
            window.setTimeout(resolve, 400 * attempt),
          );
        }
      }
    } catch (error) {
      setProblem(
        error instanceof ApiProblemError
          ? `${error.problem.detail}（请求 ID ${error.problem.requestId}）`
          : error instanceof Error
            ? error.message
            : "订单没有创建成功。",
      );
    } finally {
      setBusy(false);
    }
  };

  if (query.isLoading)
    return (
      <div className="public-page">
        <LoadingState label="正在查看商品详情" />
      </div>
    );
  if (query.isError || !query.data)
    return (
      <div className="public-page">
        <ErrorState
          title="没有找到这件商品"
          description="商品可能已下架，返回目录选择其他商品。"
        />
      </div>
    );
  const product = query.data;
  const maximum = Math.min(100, product.stock);
  const validQuantity =
    Number.isInteger(quantity) && quantity >= 1 && quantity <= maximum;
  return (
    <article className="product-detail public-page">
      <Link className="back-link" to="/#catalog">
        <ArrowLeft aria-hidden="true" />
        返回商品目录
      </Link>
      <div className="product-detail-grid">
        <ProductGallery
          key={product.productId}
          name={product.name}
          category={product.category}
          presentation={product.presentation}
        />
        <div className="product-detail-copy">
          <Badge tone={product.stock > 0 ? "healthy" : "warning"}>
            {product.stock > 0 ? `可售库存 ${product.stock}` : "已售罄"}
          </Badge>
          <p className="eyebrow">
            {product.category} / {product.productId}
          </p>
          <h1>{product.name}</h1>
          <p>{product.description ?? "当前商品没有更多描述。"}</p>
          <div className="detail-price">
            <span>普通售价</span>
            <strong>¥ {product.price}</strong>
          </div>
          {product.presentation?.specifications?.length ? (
            <dl className="product-specifications">
              {product.presentation.specifications.map((spec, index) => (
                <div key={`${spec.name}-${index}`}>
                  <dt>{spec.name}</dt>
                  <dd>{spec.value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
          <div className="product-purchase-panel">
            <label className="field quantity-field">
              <span>购买数量</span>
              <input
                type="number"
                min="1"
                max={Math.max(1, maximum)}
                step="1"
                inputMode="numeric"
                disabled={busy || product.stock === 0}
                aria-invalid={product.stock > 0 && !validQuantity}
                aria-describedby={
                  product.stock > 0 && !validQuantity
                    ? "quantity-error"
                    : undefined
                }
                value={quantity}
                onChange={(event) => setQuantity(Number(event.target.value))}
              />
            </label>
            <Button
              type="button"
              size="lg"
              disabled={!validQuantity || busy}
              data-purchase-button
              onClick={() => void buy()}
            >
              {busy
                ? "正在创建订单…"
                : product.stock === 0
                  ? "暂时售罄"
                  : session
                    ? "创建待支付订单"
                    : "登录后购买"}
            </Button>
          </div>
          {product.stock > 0 && !validQuantity ? (
            <p className="inline-problem" id="quantity-error" role="alert">
              请输入 1 至 {maximum} 之间的整数。
            </p>
          ) : null}
          <Link
            className="product-ask-agent"
            to={`/user/agent?prompt=${encodeURIComponent(`查看商品 ${product.productId}`)}`}
          >
            <MessageCircle aria-hidden="true" /> 问问购物助手
          </Link>
          <div className="safety-note">
            <ShieldCheck aria-hidden="true" />
            <p>
              下单后可在「我的订单」查看进度并完成支付。重复点击不会重复创建订单。
            </p>
          </div>
          {problem ? (
            <p className="inline-problem" role="alert">
              {problem}
            </p>
          ) : null}
        </div>
      </div>
    </article>
  );
}
