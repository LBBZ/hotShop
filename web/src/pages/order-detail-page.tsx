import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Banknote, FlaskConical } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { ApiProblemError } from "@/api/core/problem";
import { ErrorState, LoadingState } from "@/components/async-states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { getOrder } from "@/features/transactions/order-api";
import { TransactionTimeline } from "@/features/transactions/transaction-timeline";
import { useTransactionStream } from "@/features/transactions/use-transaction-stream";

const scenarios = [
  {
    id: "success",
    label: "正常成功",
    outcome: "SUCCEEDED",
    delay: "PT0S",
    duplicateCount: 1,
  },
  {
    id: "failed",
    label: "支付失败",
    outcome: "FAILED",
    delay: "PT0S",
    duplicateCount: 1,
  },
  {
    id: "delayed",
    label: "延迟回调",
    outcome: "SUCCEEDED",
    delay: "PT5S",
    duplicateCount: 1,
  },
  {
    id: "duplicate",
    label: "重复回调",
    outcome: "SUCCEEDED",
    delay: "PT0S",
    duplicateCount: 3,
  },
  {
    id: "race",
    label: "与超时关单竞态",
    outcome: "SUCCEEDED",
    delay: "PT20S",
    duplicateCount: 2,
  },
] as const;

export function OrderDetailPage() {
  const { orderId = "" } = useParams();
  const order = useQuery({
    queryKey: ["order", orderId],
    queryFn: () => getOrder(orderId),
    refetchInterval: 3000,
    enabled: Boolean(orderId),
  });
  const stream = useTransactionStream(
    orderId ? `/api/v1/orders/${orderId}/events` : null,
  );
  const [scenario, setScenario] =
    useState<(typeof scenarios)[number]["id"]>("success");
  const [busy, setBusy] = useState(false);
  const [acknowledgement, setAcknowledgement] = useState<string>();
  const [problem, setProblem] = useState<string>();
  const paymentFailed = stream.state.latest?.eventType === "PAYMENT_FAILED";

  const runScenario = async () => {
    const selected = scenarios.find((item) => item.id === scenario);
    if (!selected) return;
    setBusy(true);
    setProblem(undefined);
    setAcknowledgement(undefined);
    try {
      const payment = await apiClients.user.payments.create({ orderId });
      if (!payment.paymentNo)
        throw new Error("Mock Payment 响应缺少支付单号。");
      const accepted = await apiClients.user.payments.action({
        paymentNo: payment.paymentNo,
        mockPaymentActionRequest: {
          outcome: selected.outcome,
          delay: selected.delay,
          duplicateCount: selected.duplicateCount,
        },
      });
      setAcknowledgement(
        `Mock 回调已排队：${accepted.callbackId}。页面会等待后端最终事实，不会立即标记为已支付。`,
      );
    } catch (error) {
      setProblem(
        error instanceof ApiProblemError
          ? `${error.problem.detail}（请求 ID ${error.problem.requestId}）`
          : error instanceof Error
            ? error.message
            : "Mock 场景没有启动。",
      );
    } finally {
      setBusy(false);
    }
  };

  if (order.isLoading) return <LoadingState label="正在恢复订单事实" />;
  if (order.isError || !order.data)
    return (
      <ErrorState
        title="订单不可访问"
        description="订单不存在，或它不属于当前登录用户。两种情况都使用相同的安全响应。"
      />
    );
  return (
    <div className="transaction-page">
      <header className="dashboard-heading">
        <div>
          <p className="eyebrow">ORDER / {order.data.orderId}</p>
          <h2>Mock 收银台</h2>
          <p>只处理本地演示回调，不连接任何真实支付机构。</p>
        </div>
        <Badge
          tone={
            order.data.status === "PAID"
              ? "healthy"
              : order.data.status === "CANCELED"
                ? "warning"
                : "signal"
          }
        >
          {order.data.status}
        </Badge>
      </header>
      <section className="mock-notice" role="note">
        <FlaskConical aria-hidden="true" />
        <div>
          <strong>模拟支付，不会产生真实扣款</strong>
          <p>所有成功、失败、延迟和重复回调都由本地 Mock Payment API 生成。</p>
        </div>
      </section>
      <section className="checkout-grid">
        <div className="checkout-total">
          <span>应付金额</span>
          <strong>
            <small>¥</small>
            {order.data.totalAmount}
          </strong>
          <p>
            {order.data.currency} · 创建于{" "}
            {new Intl.DateTimeFormat("zh-CN", {
              dateStyle: "medium",
              timeStyle: "short",
            }).format(new Date(order.data.createdAt))}
          </p>
        </div>
        <div className="scenario-panel">
          <label className="field">
            <span>选择后端 Mock 场景</span>
            <select
              value={scenario}
              onChange={(event) =>
                setScenario(
                  event.target.value as (typeof scenarios)[number]["id"],
                )
              }
            >
              {scenarios.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <p>
            <AlertTriangle aria-hidden="true" />
            竞态场景的最终结果由数据库条件更新决定，不由按钮文案决定。
          </p>
          <Button
            type="button"
            onClick={() => void runScenario()}
            disabled={busy || order.data.status !== "PENDING" || paymentFailed}
          >
            <Banknote aria-hidden="true" />
            {busy ? "正在排队回调…" : "启动 Mock 支付场景"}
          </Button>
          {acknowledgement ? (
            <p className="action-ack" role="status">
              {acknowledgement}
            </p>
          ) : null}
          {problem ? (
            <p className="inline-problem" role="alert">
              {problem}
            </p>
          ) : null}
        </div>
      </section>
      <TransactionTimeline
        state={stream.state}
        connection={stream.connection}
      />
    </div>
  );
}
