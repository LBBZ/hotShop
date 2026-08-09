import { useQuery } from "@tanstack/react-query";
import { ArrowRight, Fingerprint } from "lucide-react";
import { Link, useParams } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { LoadingState } from "@/components/async-states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TransactionTimeline } from "@/features/transactions/transaction-timeline";
import { useTransactionStream } from "@/features/transactions/use-transaction-stream";

export function ReservationDetailPage() {
  const { activityId = "", reservationNo = "" } = useParams();
  const query = useQuery({
    queryKey: ["reservation", activityId, reservationNo],
    queryFn: () =>
      apiClients.user.flashSales.status({ activityId, reservationNo }),
    refetchInterval: 3000,
  });
  const stream = useTransactionStream(
    activityId && reservationNo
      ? `/api/v1/flash-sales/${activityId}/reservations/${reservationNo}/events`
      : null,
  );
  const orderId =
    [...stream.state.events].reverse().find((event) => event.orderId)
      ?.orderId ?? query.data?.orderId;
  return (
    <div className="transaction-page">
      <header className="dashboard-heading">
        <div>
          <p className="eyebrow">FLASH SALE RESERVATION</p>
          <h2>预约正在变成订单。</h2>
          <p>刷新页面或断开网络不会改变服务端事实。</p>
        </div>
        <Badge tone="signal">异步处理</Badge>
      </header>
      <section className="fact-strip" aria-label="预约事实">
        <div>
          <Fingerprint aria-hidden="true" />
          <span>RESERVATION ID</span>
          <strong>{reservationNo}</strong>
        </div>
        <div>
          <span>ACTIVITY ID</span>
          <strong>{activityId}</strong>
        </div>
        <div>
          <span>REQUEST ID</span>
          <strong>
            {stream.state.events.find((event) => event.requestId)?.requestId ??
              "正在从事件恢复"}
          </strong>
        </div>
      </section>
      {query.isLoading ? (
        <LoadingState compact label="正在核对预约事实" />
      ) : null}
      {query.data ? (
        <section className="reservation-summary">
          <div>
            <span>当前状态</span>
            <strong>{query.data.status}</strong>
          </div>
          <div>
            <span>预留金额</span>
            <strong>¥ {query.data.reservedAmount}</strong>
          </div>
          <div>
            <span>数量</span>
            <strong>{query.data.quantity}</strong>
          </div>
        </section>
      ) : null}
      {orderId ? (
        <Button asChild>
          <Link to={`/user/orders/${orderId}`}>
            进入 Mock 收银台
            <ArrowRight aria-hidden="true" />
          </Link>
        </Button>
      ) : (
        <p className="processing-note" aria-live="polite">
          订单尚未创建。状态流会在异步消费者完成后自动推进。
        </p>
      )}
      <TransactionTimeline
        state={stream.state}
        connection={stream.connection}
      />
    </div>
  );
}
