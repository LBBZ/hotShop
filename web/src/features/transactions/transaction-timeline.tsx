import { CheckCircle2, CircleDashed, Radio, Unplug } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import type { TransactionState } from "@/features/transactions/status-machine";
import type { StreamConnection } from "@/features/transactions/use-transaction-stream";

const labels = {
  RESERVED: ["库存已预留", "资格与库存已由服务端原子确认。"],
  ORDER_CREATED: ["订单已创建", "异步消费者已把预约转成数据库订单。"],
  PENDING_PAYMENT: ["等待模拟支付", "订单正在等待 Mock 收银台的最终回调。"],
  PAYMENT_FAILED: [
    "模拟支付失败",
    "后端已记录失败终态；演示其他结果需要开始一笔新订单。",
  ],
  PAID: ["支付事实已确认", "后端回调已赢得状态竞争，订单已支付。"],
  CLOSED: [
    "支付窗口已关闭",
    "超时关单先到达，后续回调不会把界面伪装成正常支付。",
  ],
  CANCELED: ["订单已取消", "订单终态来自服务端，不再接受普通支付推进。"],
  COMPENSATING: ["正在补偿库存", "成单失败后，系统正在幂等归还预留库存。"],
  COMPENSATED: ["库存补偿完成", "本次预约没有形成有效订单。"],
  LATE_SUCCEEDED: [
    "迟到支付已识别",
    "付款回调晚于关单；系统保留关闭事实并进入后续补偿语义。",
  ],
} as const;

export function TransactionTimeline({
  state,
  connection,
}: {
  state: TransactionState;
  connection: StreamConnection;
}) {
  const connectionLabel =
    connection === "live"
      ? "实时连接"
      : connection === "offline"
        ? "离线等待"
        : connection === "reconnecting"
          ? "正在重连"
          : "正在连接";
  return (
    <section
      className="transaction-receipt"
      aria-labelledby="timeline-title"
      data-connection={connection}
    >
      <header>
        <div>
          <p className="eyebrow">DURABLE EVENT RECEIPT</p>
          <h2 id="timeline-title">订单状态票据</h2>
        </div>
        <Badge
          role="status"
          aria-live="polite"
          aria-atomic="true"
          tone={
            connection === "live"
              ? "healthy"
              : connection === "offline"
                ? "warning"
                : "neutral"
          }
        >
          {connection === "live" ? (
            <Radio aria-hidden="true" />
          ) : connection === "offline" ? (
            <Unplug aria-hidden="true" />
          ) : (
            <CircleDashed aria-hidden="true" />
          )}
          {connectionLabel}
        </Badge>
      </header>
      <p className="receipt-caption" aria-live="polite">
        断线后会携带最后一个事件 ID 恢复；重复或旧事件不会让终态倒退。
      </p>
      {state.events.length ? (
        <ol>
          {state.events.map((event) => {
            const [title, description] = labels[event.eventType];
            return (
              <li key={event.eventId} data-event-type={event.eventType}>
                <span className="receipt-node">
                  <CheckCircle2 aria-hidden="true" />
                </span>
                <div>
                  <div className="receipt-event-head">
                    <strong>{title}</strong>
                    <code>EVT {event.eventId}</code>
                  </div>
                  <p>{description}</p>
                  <dl>
                    {event.requestId ? (
                      <>
                        <dt>REQUEST</dt>
                        <dd>{event.requestId}</dd>
                      </>
                    ) : null}
                    {event.reservationNo ? (
                      <>
                        <dt>RESERVATION</dt>
                        <dd>{event.reservationNo}</dd>
                      </>
                    ) : null}
                    {event.orderId ? (
                      <>
                        <dt>ORDER</dt>
                        <dd>{event.orderId}</dd>
                      </>
                    ) : null}
                  </dl>
                </div>
              </li>
            );
          })}
        </ol>
      ) : (
        <div className="receipt-wait">
          <CircleDashed aria-hidden="true" />
          <p>正在从服务端恢复第一条事实…</p>
        </div>
      )}
    </section>
  );
}
