import { useState } from "react";
import { RotateCcw } from "lucide-react";

import { findApiProblemError } from "@/api/core/problem";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { adminApi, type FailedOutboxEvent } from "@/features/admin/admin-api";
import {
  DataPanel,
  PageHeading,
  Pager,
  ResourceBoundary,
  formatMoment,
  useAdminResource,
} from "@/features/admin/admin-ui";

export function AdminOutboxPage() {
  const [cursor, setCursor] = useState<string>();
  const [target, setTarget] = useState<FailedOutboxEvent>();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{
    kind: "success" | "error";
    text: string;
  }>();
  const resource = useAdminResource(
    () => adminApi.failedOutbox(cursor),
    [cursor],
  );
  const replay = async () => {
    if (!target) return;
    setBusy(true);
    setNotice(undefined);
    try {
      await adminApi.replayOutbox(target.eventId, reason.trim());
      setNotice({
        kind: "success",
        text: `事件 ${target.eventId} 已进入现有安全重放流程。请到审计页核验结果。`,
      });
      setTarget(undefined);
      setReason("");
      setCursor(undefined);
      resource.reload();
    } catch (caught) {
      const problem = findApiProblemError(caught)?.problem;
      setNotice({
        kind: "error",
        text: problem
          ? `${problem.detail}（代码 ${problem.code}，请求 ID ${problem.requestId}）`
          : caught instanceof Error
            ? caught.message
            : "重放请求没有完成。",
      });
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="RELIABLE MESSAGING"
        title="失败 Outbox"
        description="这里只暴露现有、受后端授权和状态机约束的重放能力；不会新增自动补偿路径。"
      />
      {notice ? (
        <p
          className={`admin-notice admin-notice-${notice.kind}`}
          role={notice.kind === "error" ? "alert" : "status"}
        >
          {notice.text}
        </p>
      ) : null}
      {target ? (
        <section
          className="admin-confirm admin-confirm-risk"
          role="dialog"
          aria-modal="true"
          aria-labelledby="replay-title"
        >
          <p className="eyebrow">HIGH-RISK ACTION</p>
          <h3 id="replay-title">二次确认 Outbox 重放</h3>
          <dl>
            <div>
              <dt>资源 ID</dt>
              <dd>
                <code>{target.eventId}</code>
              </dd>
            </div>
            <div>
              <dt>事件类型</dt>
              <dd>{target.eventType}</dd>
            </div>
            <div>
              <dt>影响</dt>
              <dd>
                将失败事件切换回现有发布流程，可能再次向下游投递；不会退款、补偿或修改权限。
              </dd>
            </div>
          </dl>
          <label className="field">
            <span>操作原因（必填）</span>
            <textarea
              autoFocus
              required
              minLength={3}
              maxLength={256}
              rows={3}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <p className="admin-risk-copy">
            后端会再次校验 Administrator
            权限、事件状态与原因，并对成功和失败追加审计。
          </p>
          <div className="admin-form-actions">
            <Button
              type="button"
              disabled={busy || reason.trim().length < 3}
              onClick={() => void replay()}
            >
              <RotateCcw aria-hidden="true" />
              {busy ? "正在请求重放…" : `确认重放 ${target.eventId}`}
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
      <DataPanel
        title="失败事件"
        detail="稳定游标分页；不展示消息载荷与原始错误"
      >
        <ResourceBoundary
          resource={resource}
          emptyTitle="没有失败 Outbox"
          emptyDescription="当前查询范围没有处于失败状态的事件。"
          isEmpty={(page) => page.items.length === 0}
        >
          {(page) => (
            <>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th>事件</th>
                      <th>聚合</th>
                      <th>失败分类</th>
                      <th>尝试次数</th>
                      <th>人工重放</th>
                      <th>失败时间</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((event) => (
                      <tr key={event.eventId}>
                        <td>
                          <strong>{event.eventType}</strong>
                          <small className="mono-cell">{event.eventId}</small>
                        </td>
                        <td>
                          <span>{event.aggregateType}</span>
                          <small className="mono-cell">
                            {event.aggregateId}
                          </small>
                        </td>
                        <td>
                          <Badge tone="signal">{event.failureCategory}</Badge>
                        </td>
                        <td>
                          {event.publishAttempts}（连续{" "}
                          {event.consecutiveAttempts}）
                        </td>
                        <td>{event.manualReplayCount}</td>
                        <td>{formatMoment(event.failedAt)}</td>
                        <td>
                          <Button
                            type="button"
                            size="sm"
                            onClick={() => {
                              setTarget(event);
                              setReason("");
                              setNotice(undefined);
                            }}
                          >
                            <RotateCcw aria-hidden="true" />
                            重放
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
