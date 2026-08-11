import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import {
  adminApi,
  createAdminQueryTimeRange,
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

export function AdminExceptionsPage() {
  const [paymentTimeRange] = useState(createAdminQueryTimeRange);
  const resource = useAdminResource(async () => {
    const [status, issues, reviews, payments] = await Promise.all([
      adminApi.reconciliationStatus(),
      adminApi.reconciliationIssues(),
      adminApi.manualReviews(),
      adminApi.payments(paymentTimeRange),
    ]);
    return { status, issues, reviews, payments };
  }, [paymentTimeRange]);
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="INCIDENT TRIAGE"
        title="异常与人工处理"
        description="异常发现、支付事实和人工队列分开展示；发现异常不等于已经自动修复。"
      />
      <ResourceBoundary
        resource={resource}
        emptyTitle="暂无异常事实"
        emptyDescription="管理接口没有返回对账或人工处理事实。"
        isEmpty={() => false}
      >
        {({ status, issues, reviews, payments }) => (
          <>
            <section
              className="reconciliation-banner"
              aria-label="对账运行状态"
            >
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
                        : "未知（不宣称已修复）"}
                  </strong>
                </span>
              </div>
            </section>
            <DataPanel
              title="对账异常"
              detail={`${issues.items.length} 条当前结果`}
            >
              {issues.items.length === 0 ? (
                <p className="admin-inline-empty">
                  当前查询范围没有未关闭异常。
                </p>
              ) : (
                <div className="admin-table-wrap">
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
                      {issues.items.map((issue) => (
                        <tr key={issue.issueId}>
                          <td>
                            <strong>{issue.issueType}</strong>
                            <small className="mono-cell">
                              #{issue.issueId}
                            </small>
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
                </div>
              )}
            </DataPanel>
            <DataPanel
              title="待人工处理"
              detail="仅展示事实，不提供未经证明的自动补偿"
            >
              {reviews.items.length === 0 ? (
                <p className="admin-inline-empty">当前没有待人工处理事项。</p>
              ) : (
                <div className="admin-table-wrap">
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
                      {reviews.items.map((review) => (
                        <tr key={review.processingId}>
                          <td>
                            <strong className="mono-cell">
                              {review.eventId}
                            </strong>
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
                </div>
              )}
            </DataPanel>
            <DataPanel title="支付状态" detail="支付订单事实 · 最近 24 小时">
              {payments.items.length === 0 ? (
                <p className="admin-inline-empty">当前没有支付记录。</p>
              ) : (
                <div className="admin-table-wrap">
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
                      {payments.items.map((payment) => (
                        <tr key={payment.paymentId}>
                          <td>
                            <strong>{payment.paymentNo}</strong>
                            <small className="mono-cell">
                              {payment.paymentId}
                            </small>
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
                </div>
              )}
            </DataPanel>
          </>
        )}
      </ResourceBoundary>
    </div>
  );
}
