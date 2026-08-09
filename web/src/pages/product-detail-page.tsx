import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, PackageOpen, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useStore } from "zustand";

import { apiClients } from "@/api/clients";
import { ApiProblemError } from "@/api/core/problem";
import { userAuth } from "@/auth/domains";
import { ErrorState, LoadingState } from "@/components/async-states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  clearPurchaseIntent,
  getOrCreatePurchaseIntent,
  purchaseIntentFingerprint,
} from "@/features/transactions/purchase-intent";

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
        <LoadingState label="正在读取商品事实" />
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
  return (
    <article className="product-detail public-page">
      <Link className="back-link" to="/#catalog">
        <ArrowLeft aria-hidden="true" />
        返回商品目录
      </Link>
      <div className="product-detail-grid">
        <div className="product-detail-visual">
          <span>{product.category}</span>
          <strong>{product.productId.padStart(6, "0")}</strong>
          <PackageOpen aria-hidden="true" />
        </div>
        <div className="product-detail-copy">
          <Badge tone={product.stock > 0 ? "healthy" : "warning"}>
            {product.stock > 0 ? `可售库存 ${product.stock}` : "已售罄"}
          </Badge>
          <p className="eyebrow">PRODUCT / {product.productId}</p>
          <h1>{product.name}</h1>
          <p>{product.description ?? "当前商品没有更多描述。"}</p>
          <div className="detail-price">
            <span>普通售价</span>
            <strong>¥ {product.price}</strong>
          </div>
          <label className="field quantity-field">
            <span>购买数量</span>
            <input
              type="number"
              min="1"
              max={Math.max(1, product.stock)}
              value={quantity}
              onChange={(event) =>
                setQuantity(Math.max(1, Number(event.target.value)))
              }
            />
          </label>
          <div className="safety-note">
            <ShieldCheck aria-hidden="true" />
            <p>
              提交时创建一次购买意图；双击、超时和可恢复重试复用同一个
              Idempotency-Key。
            </p>
          </div>
          {problem ? (
            <p className="inline-problem" role="alert">
              {problem}
            </p>
          ) : null}
          <Button
            type="button"
            size="lg"
            disabled={product.stock === 0 || busy}
            onClick={() => void buy()}
          >
            {busy
              ? "正在确认唯一订单…"
              : session
                ? "创建待支付订单"
                : "登录后购买"}
          </Button>
        </div>
      </div>
    </article>
  );
}
