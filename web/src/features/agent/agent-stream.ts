export type AgentEventType =
  | "session.created"
  | "message.started"
  | "message.delta"
  | "message.completed"
  | "tool.started"
  | "tool.completed"
  | "tool.failed"
  | "purchase_draft.created"
  | "rag.completed"
  | "usage"
  | "error"
  | "done";

export interface AgentCitation {
  documentId: string;
  title: string;
  version: string;
  source: string;
  chunkId: string;
}

export interface AgentStreamEvent {
  type: AgentEventType;
  sessionId: string;
  runId: string;
  messageId?: string;
  sequence: number;
  data: Record<string, unknown>;
}

const eventTypes = new Set<AgentEventType>([
  "session.created",
  "message.started",
  "message.delta",
  "message.completed",
  "tool.started",
  "tool.completed",
  "tool.failed",
  "purchase_draft.created",
  "rag.completed",
  "usage",
  "error",
  "done",
]);

const dataKeys: Record<AgentEventType, ReadonlySet<string>> = {
  "session.created": new Set(["state", "scopes"]),
  "message.started": new Set(["state"]),
  "message.delta": new Set(["delta"]),
  "message.completed": new Set(["state"]),
  "tool.started": new Set(["tool", "resourceType"]),
  "tool.completed": new Set(["tool", "resourceType", "outcome", "summary"]),
  "tool.failed": new Set([
    "tool",
    "resourceType",
    "outcome",
    "summary",
    "code",
  ]),
  "purchase_draft.created": new Set([
    "draftId",
    "actionType",
    "items",
    "totalPriceSnapshot",
    "currency",
    "validUntil",
    "confirmationRequired",
    "nextStep",
  ]),
  "rag.completed": new Set(["outcome", "citations"]),
  usage: new Set(["inputTokens", "outputTokens", "estimatedCostUsd"]),
  error: new Set(["code", "message", "retryable"]),
  done: new Set(["state"]),
};

const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const maxFrameCharacters = 64_000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(
  value: Record<string, unknown>,
  allowed: ReadonlySet<string>,
) {
  return Object.keys(value).every((key) => allowed.has(key));
}

function safeString(value: unknown, maximum = 4_096): value is string {
  return typeof value === "string" && value.length <= maximum;
}

function citationsAreSafe(value: unknown): value is AgentCitation[] {
  return (
    Array.isArray(value) &&
    value.length <= 10 &&
    value.every(
      (item) =>
        isRecord(item) &&
        exactKeys(
          item,
          new Set(["documentId", "title", "version", "source", "chunkId"]),
        ) &&
        ["documentId", "title", "version", "source", "chunkId"].every((key) =>
          safeString(item[key], 500),
        ),
    )
  );
}

function dataShapeIsSafe(type: AgentEventType, data: Record<string, unknown>) {
  if (!exactKeys(data, dataKeys[type])) return false;
  switch (type) {
    case "session.created":
      return (
        safeString(data.state, 50) &&
        Array.isArray(data.scopes) &&
        data.scopes.length <= 20 &&
        data.scopes.every((scope) => safeString(scope, 100))
      );
    case "message.started":
    case "message.completed":
    case "done":
      return safeString(data.state, 50);
    case "message.delta":
      return safeString(data.delta, 16_000);
    case "tool.started":
      return safeString(data.tool, 100) && safeString(data.resourceType, 100);
    case "tool.completed":
    case "tool.failed":
      return (
        safeString(data.tool, 100) &&
        safeString(data.resourceType, 100) &&
        safeString(data.outcome, 30) &&
        safeString(data.summary, 1_000) &&
        (type === "tool.completed" || safeString(data.code, 100))
      );
    case "purchase_draft.created":
      return (
        safeString(data.draftId, 100) &&
        data.actionType === "CREATE_ORDER" &&
        data.confirmationRequired === true &&
        Array.isArray(data.items) &&
        data.items.length >= 1 &&
        data.items.length <= 20 &&
        data.items.every(
          (item) =>
            isRecord(item) &&
            exactKeys(
              item,
              new Set([
                "productId",
                "quantity",
                "productName",
                "unitPriceSnapshot",
                "lineAmountSnapshot",
              ]),
            ) &&
            safeString(item.productId, 19) &&
            /^[1-9][0-9]{0,18}$/u.test(item.productId) &&
            Number.isInteger(item.quantity) &&
            Number(item.quantity) >= 1 &&
            Number(item.quantity) <= 100 &&
            ["productName", "unitPriceSnapshot", "lineAmountSnapshot"].every(
              (key) => item[key] === undefined || safeString(item[key], 500),
            ),
        ) &&
        ["totalPriceSnapshot", "currency", "validUntil", "nextStep"].every(
          (key) => data[key] === undefined || safeString(data[key], 500),
        )
      );
    case "rag.completed":
      return safeString(data.outcome, 30) && citationsAreSafe(data.citations);
    case "usage":
      return [data.inputTokens, data.outputTokens, data.estimatedCostUsd].every(
        (value) =>
          typeof value === "number" && Number.isFinite(value) && value >= 0,
      );
    case "error":
      return (
        safeString(data.code, 100) &&
        safeString(data.message, 1_000) &&
        typeof data.retryable === "boolean"
      );
  }
}

export function guardAgentEvent(
  value: unknown,
  expected: { sessionId: string; runId: string },
): AgentStreamEvent | null {
  if (!isRecord(value)) return null;
  const allowedTopLevel = new Set([
    "type",
    "sessionId",
    "runId",
    "messageId",
    "sequence",
    "data",
  ]);
  if (!exactKeys(value, allowedTopLevel)) return null;
  if (
    !safeString(value.type, 40) ||
    !eventTypes.has(value.type as AgentEventType)
  ) {
    return null;
  }
  if (
    value.sessionId !== expected.sessionId ||
    value.runId !== expected.runId ||
    !uuid.test(value.sessionId) ||
    !uuid.test(value.runId) ||
    (value.messageId !== undefined &&
      (!safeString(value.messageId, 36) || !uuid.test(value.messageId))) ||
    !Number.isSafeInteger(value.sequence) ||
    Number(value.sequence) < 1 ||
    !isRecord(value.data)
  ) {
    return null;
  }
  const type = value.type as AgentEventType;
  if (!dataShapeIsSafe(type, value.data)) return null;
  return value as unknown as AgentStreamEvent;
}

export async function consumeAgentSse(
  response: Response,
  expected: { sessionId: string; runId: string },
  onEvent: (event: AgentStreamEvent) => void,
  signal: AbortSignal,
) {
  if (!response.ok || !response.body)
    throw new Error("Agent stream is unavailable.");
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("text/event-stream")) {
    throw new Error("Agent stream returned an unexpected content type.");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let lastSequence = 0;
  const abort = () => void reader.cancel();
  signal.addEventListener("abort", abort, { once: true });
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      if (buffer.length > maxFrameCharacters) {
        throw new Error("Agent stream frame exceeded its safe size.");
      }
      const frames: string[] = [];
      let delimiter = /\r\n\r\n|\n\n|\r\r/u.exec(buffer);
      while (delimiter?.index !== undefined) {
        frames.push(buffer.slice(0, delimiter.index));
        buffer = buffer.slice(delimiter.index + delimiter[0].length);
        delimiter = /\r\n\r\n|\n\n|\r\r/u.exec(buffer);
      }
      for (const rawFrame of frames) {
        const frame = rawFrame.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
        const eventLine = frame
          .split("\n")
          .find((line) => line.startsWith("event:"));
        const dataLines = frame
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).trimStart());
        if (!eventLine || dataLines.length === 0) continue;
        let parsed: unknown;
        try {
          parsed = JSON.parse(dataLines.join("\n"));
        } catch {
          continue;
        }
        const event = guardAgentEvent(parsed, expected);
        if (
          !event ||
          eventLine.slice(6).trim() !== event.type ||
          event.sequence <= lastSequence
        ) {
          continue;
        }
        lastSequence = event.sequence;
        onEvent(event);
      }
      if (done) break;
    }
  } finally {
    signal.removeEventListener("abort", abort);
    reader.releaseLock();
  }
}
