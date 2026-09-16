import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { adminApi } from "@/features/admin/admin-api";
import {
  DataPanel,
  PageHeading,
  Pager,
  ResourceBoundary,
  TraceLink,
  formatMoment,
  statusTone,
  useAdminResource,
} from "@/features/admin/admin-ui";

export function AdminAuditPage() {
  const [result, setResult] = useState("");
  const [action, setAction] = useState("");
  const [resourceType, setResourceType] = useState("");
  const [cursor, setCursor] = useState<string>();
  const resource = useAdminResource(
    () => adminApi.auditLogs(cursor, result, action, resourceType),
    [cursor, result, action, resourceType],
  );
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="ACCOUNTABILITY TRAIL"
        title="审计日志"
        description="操作者、委托身份与脱敏摘要来自追加式审计事实。动作与资源代码原样展示，未知代码也可精确筛选。"
        actions={
          <>
            <label className="compact-field">
              <span>动作代码（精确）</span>
              <input
                value={action}
                maxLength={128}
                onChange={(event) => {
                  setAction(event.target.value);
                  setCursor(undefined);
                }}
                placeholder="AGENT_TOOL_INVOKED"
              />
            </label>
            <label className="compact-field">
              <span>资源类型（精确）</span>
              <input
                value={resourceType}
                maxLength={64}
                onChange={(event) => {
                  setResourceType(event.target.value);
                  setCursor(undefined);
                }}
                placeholder="PURCHASE_DRAFT"
              />
            </label>
            <label className="compact-field">
              <span>执行结果</span>
              <select
                value={result}
                onChange={(event) => {
                  setResult(event.target.value);
                  setCursor(undefined);
                }}
              >
                <option value="">全部</option>
                <option value="SUCCESS">成功</option>
                <option value="FAILURE">失败</option>
                <option value="DENIED">拒绝</option>
              </select>
            </label>
          </>
        }
      />
      <DataPanel title="审计事实" detail="按发生时间与审计 ID 稳定倒序">
        <ResourceBoundary
          resource={resource}
          emptyTitle="没有审计记录"
          emptyDescription="当前筛选范围没有可展示的审计事实。"
          isEmpty={(page) => page.items.length === 0}
        >
          {(page) => (
            <>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th>时间 / 操作</th>
                      <th>操作者 / 委托身份</th>
                      <th>资源</th>
                      <th>结果</th>
                      <th>脱敏摘要</th>
                      <th>Request ID</th>
                      <th>Trace</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((entry, index) => (
                      <tr key={entry.auditId ?? `${entry.requestId}-${index}`}>
                        <td>
                          <strong>{entry.action}</strong>
                          <small>{formatMoment(entry.occurredAt)}</small>
                        </td>
                        <td>
                          <span>{entry.actorType}</span>
                          <small className="mono-cell">
                            {entry.actorId ?? "SYSTEM"}
                          </small>
                          {entry.delegatedActorType && (
                            <small>
                              委托：{entry.delegatedActorType} /{" "}
                              {entry.delegatedActorId ?? "—"}
                            </small>
                          )}
                        </td>
                        <td>
                          <span>{entry.resourceType}</span>
                          <small className="mono-cell">
                            {entry.resourceId ?? "—"}
                          </small>
                        </td>
                        <td>
                          <Badge tone={statusTone(entry.result)}>
                            {entry.result}
                          </Badge>
                        </td>
                        <td>
                          <code className="summary-code">
                            {JSON.stringify(entry.stateSummary)}
                          </code>
                        </td>
                        <td className="mono-cell">{entry.requestId ?? "—"}</td>
                        <td>
                          <TraceLink traceId={entry.traceId ?? undefined} />
                        </td>
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
