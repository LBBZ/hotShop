import { readStreamChunk } from "@/api/core/stream-reader";
import { userAuth } from "@/auth/domains";
import {
  isTransactionEvent,
  type TransactionEvent,
} from "@/features/transactions/status-machine";

interface StreamOptions {
  signal: AbortSignal;
  lastEventId?: string;
  idleTimeoutMs?: number;
  onOpen?: () => void;
  onEvent: (event: TransactionEvent) => void;
}

export const TRANSACTION_STREAM_IDLE_TIMEOUT_MS = 25_000;

export class TransactionStreamIdleTimeoutError extends Error {
  constructor() {
    super("交易状态连接长时间没有收到服务端心跳，准备重新连接。");
    this.name = "TransactionStreamIdleTimeoutError";
  }
}

export function parseTransactionFrame(frame: string): TransactionEvent | null {
  const data = frame
    .split(/\r\n|\r|\n/u)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n");
  if (!data) return null;
  try {
    const value: unknown = JSON.parse(data);
    return isTransactionEvent(value) ? value : null;
  } catch {
    return null;
  }
}

export async function streamTransactionEvents(
  path: string,
  {
    signal,
    lastEventId,
    idleTimeoutMs = TRANSACTION_STREAM_IDLE_TIMEOUT_MS,
    onOpen,
    onEvent,
  }: StreamOptions,
): Promise<void> {
  const headers = new Headers({ Accept: "text/event-stream" });
  if (lastEventId) headers.set("Last-Event-ID", lastEventId);
  const response = await userAuth.fetch(path, { headers, signal });
  if (!response.body) throw new Error("浏览器没有提供可读取的状态流。");
  if (
    !response.headers
      .get("content-type")
      ?.toLowerCase()
      .startsWith("text/event-stream")
  ) {
    throw new Error("交易状态接口没有返回事件流。");
  }

  onOpen?.();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let pending = "";
  const emitCompleteFrames = () => {
    const frameBoundary =
      /(?:\r\n|\r(?!\n)|(?<!\r)\n)(?:\r\n|\r(?!\n)|(?<!\r)\n)/u;
    let boundary = frameBoundary.exec(pending);
    while (boundary?.index !== undefined) {
      const frame = pending.slice(0, boundary.index);
      if (frame.length > 64_000) throw new Error("交易事件超出大小限制。");
      pending = pending.slice(boundary.index + boundary[0].length);
      const event = parseTransactionFrame(frame);
      if (event) onEvent(event);
      boundary = frameBoundary.exec(pending);
    }
  };
  try {
    while (!signal.aborted) {
      const chunk = await readStreamChunk(
        reader,
        signal,
        idleTimeoutMs,
        () => new TransactionStreamIdleTimeoutError(),
      );
      if (chunk.done) break;
      pending += decoder.decode(chunk.value, { stream: true });
      emitCompleteFrames();
      if (pending.length > 64_000) throw new Error("交易事件超出大小限制。");
    }
    if (!signal.aborted) {
      pending += decoder.decode();
      emitCompleteFrames();
      const tailEvent = parseTransactionFrame(pending);
      if (tailEvent) onEvent(tailEvent);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
