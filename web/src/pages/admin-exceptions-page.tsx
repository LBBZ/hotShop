import { useCallback, useMemo, useState, type ReactNode } from "react";
import { RefreshCw } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  adminApi,
  createAdminQueryTimeRange,
  type CursorPage,
} from "@/features/admin/admin-api";
import {
  DataPanel,
  PageHeading,
  ResourceBoundary,
  TraceLink,
  formatMoment,
  statusTone,
  useAdminResource,
} from "@/features/admin/admin-ui";

// The parent keys this component by filters and refresh revision, so a new query
// starts on page one. Each mounted list owns its cursor history and retry state.
function InvestigationPage<T>({
  title,
  loadPage,
  emptyDescription,
  children,
}: {
  title: string;
  loadPage: (cursor?: string) => Promise<CursorPage<T>>;
  emptyDescription: string;
  children: (items: T[]) => ReactNode;
}) {
  const [cursors, setCursors] = useState<Array<string | undefined>>([
    undefined,
  ]);
  const cursor = cursors.at(-1);
  const resource = useAdminResource(() => loadPage(cursor), [loadPage, cursor]);
  const nextCursor = resource.data?.hasMore
    ? resource.data.nextCursor
    : undefined;
  return (
    <div aria-busy={resource.loading}>
      <ResourceBoundary
        resource={resource}
        emptyTitle="当前页没有记录"
        emptyDescription={emptyDescription}
        isEmpty={(page) => page.items.length === 0}
      >
        {(page) => (
          <div className="admin-table-wrap">{children(page.items)}</div>
        )}
      </ResourceBoundary>
      {resource.data?.items.length || cursors.length > 1 ? (
        <nav className="admin-pager" aria-label={title + "分页"}>
          <span className="admin-muted" aria-live="polite">
            {resource.loading ? "正在读取…" : "第 " + cursors.length + " 页"}
          </span>
          <Button
            variant="ghost"
            size="sm"
            disabled={resource.loading || cursors.length === 1}
            onClick={() => setCursors([undefined])}
          >
            首页
          </Button>
          <Button
            variant="secondary"
            size="sm"
            disabled={resource.loading || cursors.length === 1}
            onClick={() => setCursors((current) => current.slice(0, -1))}
          >
            上一页
          </Button>
          <Button
            variant="secondary"
            size="sm"
            disabled={resource.loading || !nextCursor}
            onClick={() => {
              if (nextCursor) setCursors((current) => [...current, nextCursor]);
            }}
          >
            下一页
          </Button>
        </nav>
      ) : null}
    </div>
  );
}

function ReconciliationSummary() {
  const resource = useAdminResource(adminApi.reconciliationStatus, []);
  return (
    <section aria-label="对账运行状态">
      <ResourceBoundary
        resource={resource}
        emptyTitle="暂无对账摘要"
        emptyDescription="暂时没有可用的运行状态。"
        isEmpty={() => false}
      >
        {(status) => (
          <div className="reconciliation-banner">
            <div>
              <p className="eyebrow">RECONCILIATION MODE</p>
              <h3>
                {status.dryRun === true
                  ? "Dry-run：只发现，不修改"
                  : status.dryRun === false
                    ? "任务配置：非 dry-run（不代表修复完成）"
                    : "运行模式未持久化：只展示发现事实"}
              </h3>
              <p>{status.factStatement}</p>
              <p>最近检查点：{formatMoment(status.lastCheckpointAt)}</p>
            </div>
            <div className="reconciliation-numbers">
              <span>
                未关闭 <strong>{status.openIssues}</strong>
              </span>
              <span>
                严重 <strong>{status.criticalOpenIssues}</strong>
              </span>
              <span>
                自动修复{" "}
                <strong>
                  {status.autoRepair === true
                    ? "配置开启"
                    : status.autoRepair === false
                      ? "配置关闭"
                      : "未知"}
                </strong>
              </span>
            </div>
          </div>
        )}
      </ResourceBoundary>
    </section>
  );
}

export function AdminExceptionsPage() {
  const [snapshot, setSnapshot] = useState(() => ({
    at: new Date(),
    revision: 0,
  }));
  const [issueStatus, setIssueStatus] = useState("OPEN");
  const [paymentStatus, setPaymentStatus] = useState("");
  const [paymentHours, setPaymentHours] = useState(24);
  const paymentTimeRange = useMemo(
    () => createAdminQueryTimeRange(paymentHours, snapshot.at),
    [paymentHours, snapshot.at],
  );
  const loadIssues = useCallback(
    (cursor?: string) => adminApi.reconciliationIssues(cursor, issueStatus),
    [issueStatus],
  );
  const loadPayments = useCallback(
    (cursor?: string) =>
      adminApi.payments(paymentTimeRange, cursor, paymentStatus),
    [paymentTimeRange, paymentStatus],
  );
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="INCIDENT TRIAGE"
        title="异常与人工处理"
        description="查看对账异常、人工队列与支付记录。各列表独立查询，发现异常不代表已经修复。"
        actions={
          <Button
            variant="secondary"
            onClick={() =>
              setSnapshot((current) => ({
                at: new Date(),
                revision: current.revision + 1,
              }))
            }
          >
            <RefreshCw aria-hidden="true" />
            刷新全部
          </Button>
        }
      />
      <ReconciliationSummary key={snapshot.revision} />
      <DataPanel title="对账异常" detail="每页最多 20 条">
        <div className="admin-filter-bar admin-investigation-filters">
          <label className="compact-field">
            <span>异常状态</span>
            <select
              value={issueStatus}
              onChange={(event) => setIssueStatus(event.target.value)}
            >
              <option value="OPEN">未关闭</option>
              <option value="RESOLVED">已解决</option>
              <option value="IGNORED">已忽略</option>
            </select>
          </label>
        </div>
        <InvestigationPage
          key={snapshot.revision + ":" + issueStatus}
          title="对账异常"
          loadPage={loadIssues}
          emptyDescription="当前筛选范围没有对账异常。"
        >
          {(items) => (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>类型</th>
                  <th>严重度 / 状态</th>
                  <th>活动 / 预约</th>
                  <th>发现次数</th>
                  <th>证据摘要</th>
                  <th>最后发现</th>
                  <th>Trace</th>
                </tr>
              </thead>
              <tbody>
                {items.map((issue) => (
                  <tr key={issue.issueId}>
                    <td>
                      <strong>{issue.issueType}</strong>
                      <small className="mono-cell">#{issue.issueId}</small>
                    </td>
                    <td>
                      <Badge tone={statusTone(issue.severity)}>
                        {issue.severity}
                      </Badge>
                      <small>{issue.status}</small>
                    </td>
                    <td>
                      <small>活动 {issue.activityId ?? "—"}</small>
                      <small>预约 {issue.reservationNo ?? "—"}</small>
                    </td>
                    <td>{issue.occurrences}</td>
                    <td>
                      <code className="summary-code">
                        {JSON.stringify(issue.evidenceSummary)}
                      </code>
                    </td>
                    <td>{formatMoment(issue.lastSeenAt)}</td>
                    <td>
                      <TraceLink traceId={issue.traceId} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </InvestigationPage>
      </DataPanel>
      <DataPanel
        title="待人工处理"
        detail="包括待人工核验与隔离事件 · 每页最多 20 条"
      >
        <InvestigationPage
          key={snapshot.revision}
          title="待人工处理"
          loadPage={adminApi.manualReviews}
          emptyDescription="当前没有待人工处理事项。"
        >
          {(items) => (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>事件</th>
                  <th>状态</th>
                  <th>原因</th>
                  <th>尝试</th>
                  <th>最后错误</th>
                  <th>更新时间</th>
                  <th>Trace</th>
                </tr>
              </thead>
              <tbody>
                {items.map((review) => (
                  <tr key={review.processingId}>
                    <td>
                      <strong className="mono-cell">{review.eventId}</strong>
                      <small>预约 {review.reservationNo ?? "—"}</small>
                    </td>
                    <td>
                      <Badge tone={statusTone(review.status)}>
                        {review.status}
                      </Badge>
                    </td>
                    <td>{review.reasonCode}</td>
                    <td>{review.attempts}</td>
                    <td>{review.lastError ?? "—"}</td>
                    <td>{formatMoment(review.updatedAt)}</td>
                    <td>
                      <TraceLink traceId={review.traceId} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </InvestigationPage>
      </DataPanel>
      <DataPanel
        title="支付状态"
        detail={"查询截至 " + formatMoment(snapshot.at)}
      >
        <div className="admin-filter-bar admin-investigation-filters">
          <label className="compact-field">
            <span>支付结果</span>
            <select
              value={paymentStatus}
              onChange={(event) => setPaymentStatus(event.target.value)}
            >
              <option value="">全部</option>
              <option value="PENDING">待支付</option>
              <option value="SUCCEEDED">支付成功</option>
              <option value="FAILED">支付失败</option>
              <option value="CLOSED">已关闭</option>
              <option value="LATE_SUCCEEDED">超时后到账</option>
            </select>
          </label>
          <label className="compact-field">
            <span>支付时间范围</span>
            <select
              value={paymentHours}
              onChange={(event) => setPaymentHours(Number(event.target.value))}
            >
              <option value={24}>最近 24 小时</option>
              <option value={168}>最近 7 天</option>
              <option value={744}>最近 31 天</option>
            </select>
          </label>
        </div>
        <InvestigationPage
          key={snapshot.revision + ":" + paymentStatus + ":" + paymentHours}
          title="支付状态"
          loadPage={loadPayments}
          emptyDescription="当前筛选范围没有支付记录。"
        >
          {(items) => (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>支付单</th>
                  <th>订单 ID</th>
                  <th>渠道</th>
                  <th>状态</th>
                  <th>金额</th>
                  <th>更新时间</th>
                </tr>
              </thead>
              <tbody>
                {items.map((payment) => (
                  <tr key={payment.paymentId}>
                    <td>
                      <strong>{payment.paymentNo}</strong>
                      <small className="mono-cell">{payment.paymentId}</small>
                    </td>
                    <td className="mono-cell">{payment.orderId}</td>
                    <td>{payment.provider}</td>
                    <td>
                      <Badge tone={statusTone(payment.status)}>
                        {payment.status}
                      </Badge>
                    </td>
                    <td>
                      {payment.currency} {payment.amount}
                    </td>
                    <td>{formatMoment(payment.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </InvestigationPage>
      </DataPanel>
    </div>
  );
}
