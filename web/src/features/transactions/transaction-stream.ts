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

function readWithIdleTimeout(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal,
  idleTimeoutMs: number,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (
      outcome:
        | { result: ReadableStreamReadResult<Uint8Array> }
        | { error: unknown },
    ) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      signal.removeEventListener("abort", abort);
      if ("result" in outcome) resolve(outcome.result);
      else
        reject(
          outcome.error instanceof Error
            ? outcome.error
            : new Error("交易状态流读取失败。"),
        );
    };
    const abort = () => {
      void reader.cancel().catch(() => undefined);
      finish({ error: new DOMException("状态流读取已取消", "AbortError") });
    };
    const timer = window.setTimeout(() => {
      void reader.cancel().catch(() => undefined);
      finish({ error: new TransactionStreamIdleTimeoutError() });
    }, idleTimeoutMs);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) {
      abort();
      return;
    }
    void reader.read().then(
      (result) => finish({ result }),
      (error: unknown) => finish({ error }),
    );
  });
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
      pending = pending.slice(boundary.index + boundary[0].length);
      const event = parseTransactionFrame(frame);
      if (event) onEvent(event);
      boundary = frameBoundary.exec(pending);
    }
  };
  try {
    while (!signal.aborted) {
      const chunk = await readWithIdleTimeout(reader, signal, idleTimeoutMs);
      if (chunk.done) break;
      pending += decoder.decode(chunk.value, { stream: true });
      emitCompleteFrames();
    }
    if (!signal.aborted) {
      pending += decoder.decode();
      emitCompleteFrames();
      const tailEvent = parseTransactionFrame(pending);
      if (tailEvent) onEvent(tailEvent);
    }
  } finally {
    reader.releaseLock();
  }
}
