import { useState } from "react";
import {
  AlertTriangle,
  Boxes,
  CalendarClock,
  CreditCard,
  PackageCheck,
  ReceiptText,
  Send,
  Siren,
} from "lucide-react";

import { adminApi } from "@/features/admin/admin-api";
import {
  PageHeading,
  ResourceBoundary,
  formatMoment,
  useAdminResource,
} from "@/features/admin/admin-ui";

const metricDefinitions = [
  ["productsCreated", "商品", Boxes],
  ["activitiesCreated", "活动", CalendarClock],
  ["ordersCreated", "订单", ReceiptText],
  ["reservationsCreated", "预约", PackageCheck],
  ["paymentsCreated", "支付", CreditCard],
  ["failedOutboxUpdated", "失败 Outbox", Send],
  ["openReconciliationIssues", "未关闭异常", AlertTriangle],
  ["pendingManualReviews", "待人工处理", Siren],
] as const;

export function AdminDashboard() {
  const [windowHours, setWindowHours] = useState(24);
  const resource = useAdminResource(
    () => adminApi.overview(windowHours),
    [windowHours],
  );

  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="OPERATIONS OVERVIEW"
        title="运营脉冲"
        description="低基数统计来自 Admin 数据库只读查询；时间范围明确且有界，不进行无限历史全表扫描。"
        actions={
          <label className="compact-field">
            <span>统计窗口</span>
            <select
              value={windowHours}
              onChange={(event) => setWindowHours(Number(event.target.value))}
            >
              <option value={1}>最近 1 小时</option>
              <option value={6}>最近 6 小时</option>
              <option value={24}>最近 24 小时</option>
              <option value={72}>最近 72 小时</option>
            </select>
          </label>
        }
      />
      <ResourceBoundary
        resource={resource}
        emptyTitle="暂时没有统计结果"
        emptyDescription="Admin API 没有返回当前窗口的聚合事实。"
        isEmpty={() => false}
      >
        {(overview) => (
          <>
            <section className="admin-metric-grid" aria-label="运营统计">
              {metricDefinitions.map(([key, label, Icon]) => (
                <article className="admin-metric-card" key={key}>
                  <Icon aria-hidden="true" />
                  <span>{label}</span>
                  <strong>{overview[key].toLocaleString("zh-CN")}</strong>
                </article>
              ))}
            </section>
            <section className="admin-fact-strip" aria-label="统计口径">
              <div>
                <span>范围起点</span>
                <strong>{formatMoment(overview.rangeFrom)}</strong>
              </div>
              <div>
                <span>范围终点</span>
                <strong>{formatMoment(overview.rangeTo)}</strong>
              </div>
              <div>
                <span>数据来源</span>
                <strong>{overview.source}</strong>
              </div>
              <div>
                <span>生成时间</span>
                <strong>{formatMoment(overview.generatedAt)}</strong>
              </div>
            </section>
          </>
        )}
      </ResourceBoundary>
    </div>
  );
}
