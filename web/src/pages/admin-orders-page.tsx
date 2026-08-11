import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import {
  adminApi,
  createAdminQueryTimeRange,
  type AdminOrder,
} from "@/features/admin/admin-api";
import {
  DataPanel,
  PageHeading,
  Pager,
  ResourceBoundary,
  formatMoment,
  statusTone,
  useAdminResource,
} from "@/features/admin/admin-ui";

export function AdminOrdersPage() {
  const [status, setStatus] = useState("");
  const [cursor, setCursor] = useState<string>();
  const [timeRange, setTimeRange] = useState(createAdminQueryTimeRange);
  const resource = useAdminResource(
    () => adminApi.orders(timeRange, cursor, status),
    [cursor, status, timeRange],
  );
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="TRANSACTION FACTS"
        title="订单查询"
        description="服务端稳定游标分页；筛选条件由后端白名单校验。"
        actions={
          <label className="compact-field">
            <span>订单状态</span>
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setCursor(undefined);
                setTimeRange(createAdminQueryTimeRange());
              }}
            >
              <option value="">全部</option>
              <option value="PENDING">待支付</option>
              <option value="PAID">已支付</option>
              <option value="COMPLETED">已完成</option>
              <option value="CANCELED">已取消</option>
            </select>
          </label>
        }
      />
      <DataPanel title="订单事实" detail="每页最多 20 条">
        <ResourceBoundary
          resource={resource}
          emptyTitle="没有符合条件的订单"
          emptyDescription="调整状态筛选后重试。"
          isEmpty={(page) => page.items.length === 0}
        >
          {(page) => (
            <>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th>订单 ID</th>
                      <th>用户 ID</th>
                      <th>状态</th>
                      <th>金额</th>
                      <th>商品项</th>
                      <th>创建时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((order: AdminOrder) => (
                      <tr key={order.orderId}>
                        <td className="mono-cell">{order.orderId}</td>
                        <td className="mono-cell">{order.userId}</td>
                        <td>
                          <Badge tone={statusTone(order.status)}>
                            {order.status}
                          </Badge>
                        </td>
                        <td>
                          {order.currency} {order.totalAmount}
                        </td>
                        <td>{order.items.length}</td>
                        <td>{formatMoment(order.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager
                page={page}
                loading={resource.loading}
                onNext={setCursor}
              />
            </>
          )}
        </ResourceBoundary>
      </DataPanel>
    </div>
  );
}
