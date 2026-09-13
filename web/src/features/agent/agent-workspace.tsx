import {
  AlertTriangle,
  Ban,
  Bot,
  CheckCircle2,
  CircleStop,
  FileText,
  Radio,
  Send,
  ShieldCheck,
  Wrench,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { apiEnvironment } from "@/api/core/environment";
import { adminAuth, userAuth } from "@/auth/domains";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  consumeAgentSse,
  type AgentCitation,
  type AgentStreamEvent,
} from "@/features/agent/agent-stream";
import { TransactionTimeline } from "@/features/transactions/transaction-timeline";
import { useTransactionStream } from "@/features/transactions/use-transaction-stream";

type Boundary = "user" | "admin";
type Phase = "idle" | "creating" | "streaming" | "done" | "error" | "cancelled";

interface DraftItem {
  productId: string;
  quantity: number;
  productName?: string;
  unitPriceSnapshot?: string;
  lineAmountSnapshot?: string;
}

interface PurchaseDraft {
  draftId: string;
  actionType: "CREATE_ORDER";
  items: DraftItem[];
  totalPriceSnapshot?: string;
  currency?: string;
  validUntil?: string;
  confirmationRequired: true;
  nextStep?: string;
}

function parsePurchaseDraft(answer: string): PurchaseDraft | null {
  try {
    const parsed = JSON.parse(answer) as unknown;
    if (typeof parsed !== "object" || parsed === null) return null;
    const value = parsed as Record<string, unknown>;
    const draft = value.purchaseDraft;
    if (typeof draft !== "object" || draft === null || Array.isArray(draft))
      return null;
    const fields = draft as Record<string, unknown>;
    if (
      typeof fields.draftId !== "string" ||
      fields.actionType !== "CREATE_ORDER" ||
      fields.confirmationRequired !== true ||
      !Array.isArray(fields.items)
    ) {
      return null;
    }
    const items = fields.items.flatMap((item): DraftItem[] => {
      if (typeof item !== "object" || item === null || Array.isArray(item))
        return [];
      const line = item as Record<string, unknown>;
      if (
        typeof line.productId !== "string" ||
        !Number.isInteger(line.quantity)
      )
        return [];
      return [
        {
          productId: line.productId,
          quantity: Number(line.quantity),
          productName:
            typeof line.productName === "string" ? line.productName : undefined,
          unitPriceSnapshot:
            typeof line.unitPriceSnapshot === "string"
              ? line.unitPriceSnapshot
              : undefined,
          lineAmountSnapshot:
            typeof line.lineAmountSnapshot === "string"
              ? line.lineAmountSnapshot
              : undefined,
        },
      ];
    });
    if (items.length !== fields.items.length || items.length === 0) return null;
    return {
      draftId: fields.draftId,
      actionType: "CREATE_ORDER",
      items,
      totalPriceSnapshot:
        typeof fields.totalPriceSnapshot === "string"
          ? fields.totalPriceSnapshot
          : undefined,
      currency:
        typeof fields.currency === "string" ? fields.currency : undefined,
      validUntil:
        typeof fields.validUntil === "string" ? fields.validUntil : undefined,
      confirmationRequired: true,
      nextStep:
        typeof fields.nextStep === "string" ? fields.nextStep : undefined,
    };
  } catch {
    return null;
  }
}

export function AgentWorkspace({ boundary }: { boundary: Boundary }) {
  const [question, setQuestion] = useState("");
  const [phase, setPhase] = useState<Phase>("idle");
  const [answer, setAnswer] = useState("");
  const [stages, setStages] = useState<AgentStreamEvent[]>([]);
  const [citations, setCitations] = useState<AgentCitation[]>([]);
  const [draft, setDraft] = useState<PurchaseDraft | null>(null);
  const [orderId, setOrderId] = useState<string>();
  const [problem, setProblem] = useState<string>();
  const [confirming, setConfirming] = useState(false);
  const generation = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const activeRun = useRef<string | null>(null);
  const confirmationLock = useRef(false);
  const answerRef = useRef("");
  const draftRef = useRef<PurchaseDraft | null>(null);
  const answerHeading = useRef<HTMLHeadingElement>(null);
  const auth = boundary === "user" ? userAuth : adminAuth;
  const transaction = useTransactionStream(
    orderId ? `/api/v1/orders/${orderId}/events` : null,
  );

  const cancel = async (announce = true) => {
    generation.current += 1;
    controller.current?.abort();
    const runId = activeRun.current;
    activeRun.current = null;
    if (runId) {
      try {
        if (boundary === "user") {
          await apiClients.agent.user.cancelRunApiV1AgentRunsRunIdDelete({
            runId,
          });
        } else {
          await apiClients.agent.admin.cancelRunAdminApiV1AgentRunsRunIdDelete({
            runId,
          });
        }
      } catch {
        // Best-effort cancellation: the abort already prevents late UI events.
      }
    }
    if (announce) setPhase("cancelled");
  };

  useEffect(() => {
    const activeBoundary = boundary;
    return () => {
      if (activeBoundary === boundary) void cancel(false);
    };
    // Cleanup is intentionally tied only to this mounted identity boundary.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boundary]);

  useEffect(() => {
    if (phase === "done" || phase === "error") answerHeading.current?.focus();
  }, [phase]);

  const submit = async () => {
    const content = question.trim();
    if (!content || phase === "creating" || phase === "streaming") return;
    await cancel(false);
    const currentGeneration = generation.current;
    const abortController = new AbortController();
    controller.current = abortController;
    setPhase("creating");
    setProblem(undefined);
    setAnswer("");
    answerRef.current = "";
    setStages([]);
    setCitations([]);
    setDraft(null);
    draftRef.current = null;
    setOrderId(undefined);
    try {
      const session =
        boundary === "user"
          ? await apiClients.agent.user.createSessionApiV1AgentSessionsPost({
              userSessionRequest: {
                scopes: new Set([
                  "catalog:read",
                  "orders:self:read",
                  "reservations:self:read",
                  "purchase-drafts:create",
                ]),
              },
            })
          : await apiClients.agent.admin.createSessionAdminApiV1AgentSessionsPost(
              {
                body: {},
              },
            );
      if (generation.current !== currentGeneration) return;
      const message =
        boundary === "user"
          ? await apiClients.agent.user.addMessageApiV1AgentSessionsSessionIdMessagesPost(
              {
                sessionId: session.id,
                messageRequest: { content },
              },
            )
          : await apiClients.agent.admin.addMessageAdminApiV1AgentSessionsSessionIdMessagesPost(
              {
                sessionId: session.id,
                messageRequest: { content },
              },
            );
      const run =
        boundary === "user"
          ? await apiClients.agent.user.startRunApiV1AgentSessionsSessionIdRunsPost(
              {
                sessionId: session.id,
                runRequest: { messageId: message.id },
              },
            )
          : await apiClients.agent.admin.startRunAdminApiV1AgentSessionsSessionIdRunsPost(
              {
                sessionId: session.id,
                runRequest: { messageId: message.id },
              },
            );
      if (generation.current !== currentGeneration) return;
      activeRun.current = run.id;
      setPhase("streaming");
      const route =
        boundary === "user"
          ? `/api/v1/agent/runs/${run.id}/events`
          : `/admin/api/v1/agent/runs/${run.id}/events`;
      const response = await auth.fetch(
        `${apiEnvironment.agentBaseUrl}${route}`,
        {
          headers: { Accept: "text/event-stream" },
          signal: abortController.signal,
        },
      );
      await consumeAgentSse(
        response,
        { sessionId: session.id, runId: run.id },
        (event) => {
          if (
            generation.current !== currentGeneration ||
            abortController.signal.aborted
          )
            return;
          setStages((current) => [...current, event]);
          if (event.type === "message.delta") {
            answerRef.current += String(event.data.delta);
            setAnswer(answerRef.current);
          } else if (event.type === "rag.completed") {
            setCitations(event.data.citations as AgentCitation[]);
          } else if (event.type === "purchase_draft.created") {
            const structuredDraft = parsePurchaseDraft(
              JSON.stringify({ purchaseDraft: event.data }),
            );
            if (structuredDraft) {
              draftRef.current = structuredDraft;
              setDraft(structuredDraft);
            }
          } else if (event.type === "error") {
            setProblem(String(event.data.message));
          }
        },
        abortController.signal,
      );
      if (
        generation.current !== currentGeneration ||
        abortController.signal.aborted
      )
        return;
      activeRun.current = null;
      setDraft(
        boundary === "user"
          ? (draftRef.current ?? parsePurchaseDraft(answerRef.current))
          : null,
      );
      setPhase("done");
    } catch (error) {
      if (
        abortController.signal.aborted ||
        generation.current !== currentGeneration
      )
        return;
      activeRun.current = null;
      setProblem(
        error instanceof Error
          ? error.message
          : "Agent 服务暂时不可用；商品和交易核心仍可继续使用。",
      );
      setPhase("error");
    }
  };

  const confirmDraft = async () => {
    if (!draft || confirmationLock.current) return;
    confirmationLock.current = true;
    setConfirming(true);
    setProblem(undefined);
    try {
      const issued = await apiClients.user.purchaseConfirmations.issue({
        draftId: draft.draftId,
        purchaseConfirmationIssueRequest: { actionType: "CREATE_ORDER" },
      });
      const confirmationToken = issued.confirmationToken;
      if (!confirmationToken) throw new Error("确认凭证签发失败。");
      const result = await apiClients.user.purchaseConfirmations.consume({
        purchaseConfirmationConsumeRequest: {
          confirmationToken,
          draftId: draft.draftId,
          actionType: "CREATE_ORDER",
          items: draft.items.map(({ productId, quantity }) => ({
            productId,
            quantity,
          })),
        },
      });
      if (!result.orderId) throw new Error("确认成功但订单响应不完整。");
      setOrderId(result.orderId);
      setDraft(null);
    } catch (error) {
      setProblem(
        error instanceof Error
          ? error.message
          : "购买确认失败，未创建第二个订单。",
      );
    } finally {
      setConfirming(false);
      confirmationLock.current = false;
    }
  };

  const visibleAnswer = draft
    ? "购买草稿已生成。请核对商品和数量后主动确认。"
    : answer;

  return (
    <div className="agent-workspace">
      <header className="dashboard-heading agent-heading">
        <div>
          <p className="eyebrow">
            {boundary === "user" ? "PERSONAL AGENT" : "SAFE OPERATIONS AGENT"}
          </p>
          <h2>{boundary === "user" ? "购物协作台" : "低风险排障协作台"}</h2>
          <p>
            {boundary === "user"
              ? "静态政策带引用；价格、库存和本人订单始终查询实时事实。"
              : "只读统计、异常摘要和低风险配置草稿；草稿不会直接修改配置。"}
          </p>
        </div>
        <Badge tone={phase === "error" ? "warning" : "healthy"}>
          <ShieldCheck aria-hidden="true" />{" "}
          {phase === "error" ? "降级" : "边界已锁定"}
        </Badge>
      </header>

      <section className="agent-console" aria-labelledby="agent-input-title">
        <div className="agent-prompt-panel">
          <div>
            <Bot aria-hidden="true" />
            <h3 id="agent-input-title">告诉 Agent 你要完成什么</h3>
          </div>
          <label className="field">
            <span>请求</span>
            <textarea
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder={
                boundary === "user"
                  ? "例如：购买商品 916001 数量 2 件"
                  : "例如：查看异常摘要"
              }
              maxLength={16_000}
              rows={5}
            />
          </label>
          <div className="agent-actions">
            <Button
              type="button"
              disabled={
                !question.trim() ||
                phase === "creating" ||
                phase === "streaming"
              }
              onClick={() => void submit()}
            >
              <Send aria-hidden="true" /> 发送请求
            </Button>
            {phase === "creating" || phase === "streaming" ? (
              <Button
                type="button"
                variant="ghost"
                onClick={() => void cancel()}
              >
                <CircleStop aria-hidden="true" /> 取消运行
              </Button>
            ) : null}
          </div>
        </div>

        <aside className="agent-stage-rail" aria-label="Agent 运行阶段">
          <h3>结构化阶段</h3>
          <ol>
            {stages.map((event) => (
              <li
                key={`${event.runId}-${event.sequence}`}
                data-agent-event={event.type}
              >
                {event.type.startsWith("tool.") ? (
                  <Wrench aria-hidden="true" />
                ) : (
                  <Radio aria-hidden="true" />
                )}
                <span>{event.type}</span>
                <code>{event.sequence}</code>
              </li>
            ))}
          </ol>
          {!stages.length ? (
            <p>发送请求后，这里只显示阶段，不显示模型思维过程。</p>
          ) : null}
        </aside>
      </section>

      <section className="agent-answer" aria-live="polite" aria-atomic="false">
        <h3 ref={answerHeading} tabIndex={-1}>
          {problem ? "请求需要处理" : "Agent 回答"}
        </h3>
        {problem ? (
          <div className="agent-problem" role="alert">
            <AlertTriangle aria-hidden="true" />
            <p>{problem}</p>
          </div>
        ) : visibleAnswer ? (
          <p className="agent-answer-copy">{visibleAnswer}</p>
        ) : (
          <p>尚无回答。</p>
        )}
        {citations.length ? (
          <ul className="agent-citations" aria-label="引用资料">
            {citations.map((citation) => (
              <li key={`${citation.documentId}-${citation.chunkId}`}>
                <FileText aria-hidden="true" />
                <div>
                  <strong>{citation.title}</strong>
                  <span>
                    {citation.source} · v{citation.version}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        ) : null}
        {boundary === "admin" ? (
          <div className="agent-safety-note" role="note">
            <Ban aria-hidden="true" />
            <p>
              退款、补偿、Outbox 重放、用户封禁、权限和密钥修改永不开放给
              Agent。
            </p>
          </div>
        ) : null}
      </section>

      {draft ? (
        <section
          className="purchase-draft"
          aria-labelledby="purchase-draft-title"
        >
          <header>
            <div>
              <p className="eyebrow">REVIEW BEFORE COMMIT</p>
              <h3 id="purchase-draft-title">购买草稿</h3>
            </div>
            <Badge tone="warning">尚未创建订单</Badge>
          </header>
          <ul>
            {draft.items.map((item) => (
              <li key={item.productId}>
                <strong>{item.productName ?? `商品 ${item.productId}`}</strong>
                <span>数量 {item.quantity}</span>
                {item.lineAmountSnapshot ? (
                  <span>¥{item.lineAmountSnapshot}</span>
                ) : null}
              </li>
            ))}
          </ul>
          <p className="purchase-risk">
            确认时会重新检查实时库存和价格。确认凭证只在内存中签发并立即消费，不会显示或保存。
          </p>
          <Button
            type="button"
            disabled={confirming}
            onClick={() => void confirmDraft()}
          >
            <CheckCircle2 aria-hidden="true" />{" "}
            {confirming ? "正在确认" : "确认并创建订单"}
          </Button>
        </section>
      ) : null}

      {orderId ? (
        <section
          className="agent-order-result"
          aria-labelledby="agent-order-title"
        >
          <h3 id="agent-order-title">订单已由真实交易服务创建</h3>
          <p>订单 {orderId} 已提交。下方状态来自可恢复的交易时间线 SSE。</p>
          <Link to={`/user/orders/${orderId}`}>打开订单与 Mock 收银台</Link>
          <TransactionTimeline
            state={transaction.state}
            connection={transaction.connection}
          />
        </section>
      ) : null}
    </div>
  );
}
