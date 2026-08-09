import { useInfiniteQuery } from "@tanstack/react-query";
import { ArrowRight, ReceiptText } from "lucide-react";
import { Link } from "react-router-dom";

import { apiClients } from "@/api/clients";
import {
  EmptyState,
  ErrorState,
  LoadingState,
} from "@/components/async-states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

export function OrderListPage() {
  const query = useInfiniteQuery({
    queryKey: ["my-orders"],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      apiClients.user.orders.getOrders({ limit: 10, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const orders = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="transaction-page">
      <header className="dashboard-heading">
        <div>
          <p className="eyebrow">MY ORDERS</p>
          <h2>只呈现属于你的交易。</h2>
          <p>列表使用服务端稳定游标，不依赖前端拼接 userId。</p>
        </div>
        <Badge tone="healthy">资源归属已校验</Badge>
      </header>
      {query.isLoading ? <LoadingState label="正在读取订单" /> : null}
      {query.isError ? (
        <ErrorState
          description="订单列表没有同步成功。"
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!query.isLoading && !query.isError && orders.length === 0 ? (
        <EmptyState
          title="还没有订单"
          description="从首页选择普通商品或参加正在进行的秒杀活动。"
          action={
            <Button asChild>
              <Link to="/">去浏览商品</Link>
            </Button>
          }
        />
      ) : null}
      <div className="order-list">
        {orders.map((order) => (
          <article key={order.orderId} className="order-row">
            <ReceiptText aria-hidden="true" />
            <div>
              <span>
                {new Intl.DateTimeFormat("zh-CN", {
                  dateStyle: "medium",
                  timeStyle: "short",
                }).format(order.createdAt)}
              </span>
              <strong>{order.orderId}</strong>
            </div>
            <div>
              <Badge
                tone={
                  order.status === "PAID"
                    ? "healthy"
                    : order.status === "CANCELED"
                      ? "warning"
                      : "neutral"
                }
              >
                {order.status}
              </Badge>
              <strong>¥ {order.totalAmount}</strong>
            </div>
            <Button asChild variant="secondary" size="sm">
              <Link to={`/user/orders/${order.orderId}`}>
                查看状态
                <ArrowRight aria-hidden="true" />
              </Link>
            </Button>
          </article>
        ))}
      </div>
      {query.hasNextPage ? (
        <Button
          type="button"
          variant="secondary"
          disabled={query.isFetchingNextPage}
          onClick={() => void query.fetchNextPage()}
        >
          {query.isFetchingNextPage ? "正在读取…" : "加载更多订单"}
        </Button>
      ) : null}
    </div>
  );
}
