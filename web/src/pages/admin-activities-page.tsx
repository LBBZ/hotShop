import { useState } from "react";
import { DatabaseZap } from "lucide-react";

import { findApiProblemError } from "@/api/core/problem";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { adminApi, type AdminActivity } from "@/features/admin/admin-api";
import {
  DataPanel,
  PageHeading,
  Pager,
  ResourceBoundary,
  formatMoment,
  statusTone,
  useAdminResource,
} from "@/features/admin/admin-ui";

export function AdminActivitiesPage() {
  const [status, setStatus] = useState("");
  const [cursor, setCursor] = useState<string>();
  const [target, setTarget] = useState<AdminActivity>();
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<string>();
  const [busy, setBusy] = useState(false);
  const resource = useAdminResource(
    () => adminApi.activities(cursor, status),
    [cursor, status],
  );
  const load = async () => {
    if (!target) return;
    setBusy(true);
    setResult(undefined);
    try {
      const response = await adminApi.loadActivity(target.activityId, reason);
      const loadResult =
        typeof response.result === "string"
          ? response.result
          : typeof response.detail === "string"
            ? response.detail
            : "成功";
      setResult(`活动 ${target.activityId} 已完成加载校验：${loadResult}`);
      setTarget(undefined);
      setReason("");
      resource.reload();
    } catch (caught) {
      const problem = findApiProblemError(caught)?.problem;
      setResult(
        problem
          ? `${problem.detail}（请求 ID ${problem.requestId}）`
          : "活动加载没有完成。",
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="FLASH SALE FACTS"
        title="秒杀活动"
        description="展示数据库活动事实；加载仅触发现有安全能力，并返回 Redis / MySQL 对账结果。"
        actions={
          <label className="compact-field">
            <span>活动状态</span>
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setCursor(undefined);
              }}
            >
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="ACTIVE">进行中</option>
              <option value="ENDED">已结束</option>
            </select>
          </label>
        }
      />
      {result ? (
        <p className="admin-notice" role="status">
          {result}
        </p>
      ) : null}
      {target ? (
        <section
          className="admin-confirm"
          role="dialog"
          aria-modal="true"
          aria-labelledby="load-activity-title"
        >
          <h3 id="load-activity-title">加载活动并核验事实</h3>
          <p>
            活动 ID：<code>{target.activityId}</code>。影响：以已校验的 MySQL
            事实加载 Redis，并返回 dry reconciliation
            结果；不会自动修复已发现异常。
          </p>
          <label className="field">
            <span>操作原因</span>
            <input
              autoFocus
              required
              minLength={3}
              maxLength={256}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <div className="admin-form-actions">
            <Button
              type="button"
              disabled={busy || reason.trim().length < 3}
              onClick={() => void load()}
            >
              <DatabaseZap aria-hidden="true" />
              {busy ? "正在核验…" : "确认加载"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setTarget(undefined)}
            >
              取消
            </Button>
          </div>
        </section>
      ) : null}
      <DataPanel title="活动配置" detail="每页最多 20 条">
        <ResourceBoundary
          resource={resource}
          emptyTitle="当前没有活动"
          emptyDescription="没有符合筛选条件的秒杀活动事实。"
          isEmpty={(page) => page.items.length === 0}
        >
          {(page) => (
            <>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th>活动</th>
                      <th>商品 ID</th>
                      <th>状态</th>
                      <th>秒杀价</th>
                      <th>可用 / 总库存</th>
                      <th>时间窗口</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((activity) => (
                      <tr key={activity.activityId}>
                        <td>
                          <strong>{activity.activityCode}</strong>
                          <small className="mono-cell">
                            {activity.activityId} · v{activity.version}
                          </small>
                        </td>
                        <td className="mono-cell">{activity.productId}</td>
                        <td>
                          <Badge tone={statusTone(activity.status)}>
                            {activity.status}
                          </Badge>
                        </td>
                        <td>¥ {activity.salePrice}</td>
                        <td>
                          {activity.availableStock} / {activity.totalStock}
                        </td>
                        <td>
                          <small>{formatMoment(activity.startsAt)}</small>
                          <small>至 {formatMoment(activity.endsAt)}</small>
                        </td>
                        <td>
                          <Button
                            type="button"
                            variant="secondary"
                            size="sm"
                            onClick={() => setTarget(activity)}
                          >
                            <DatabaseZap aria-hidden="true" />
                            加载并核验
                          </Button>
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
