import { beforeEach, describe, expect, it, vi } from "vitest";

const { authenticatedFetch } = vi.hoisted(() => ({
  authenticatedFetch: vi.fn(),
}));

vi.mock("@/auth/domains", () => ({
  userAuth: { fetch: authenticatedFetch },
}));

import {
  parseTransactionFrame,
  streamTransactionEvents,
  TransactionStreamIdleTimeoutError,
} from "@/features/transactions/transaction-stream";
import { transactionReconnectDelay } from "@/features/transactions/use-transaction-stream";

const event = {
  eventId: "41",
  resourceType: "ORDER",
  resourceId: "ord_41",
  orderId: "ord_41",
  eventType: "PAID",
  requestId: "req-41",
  detailCode: "MOCK_PAYMENT_CONFIRMED",
  occurredAt: "2026-08-08T08:00:00Z",
};

describe("transaction SSE", () => {
  beforeEach(() => authenticatedFetch.mockReset());

  it("parses multiline data and ignores malformed or heartbeat frames", () => {
    const json = JSON.stringify(event);
    expect(parseTransactionFrame(`id: 41\ndata: ${json}`)).toEqual(event);
    expect(parseTransactionFrame(": heartbeat")).toBeNull();
    expect(parseTransactionFrame("data: {broken-json")).toBeNull();
  });

  it("does not emit malformed required or optional fields into the timeline", async () => {
    const malformed = { ...event, requestId: { value: "req-41" }, orderId: "" };
    const payload = `data: ${JSON.stringify(malformed)}\n\ndata: ${JSON.stringify(event)}\n\n`;
    authenticatedFetch.mockResolvedValue(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(new TextEncoder().encode(payload));
            controller.close();
          },
        }),
      ),
    );
    const received: unknown[] = [];

    await streamTransactionEvents("/orders/ord_41/events", {
      signal: new AbortController().signal,
      onEvent: (receivedEvent) => received.push(receivedEvent),
    });

    expect(received).toEqual([event]);
  });

  it("carries Last-Event-ID and parses frames split across network chunks", async () => {
    const bytes = new TextEncoder();
    const payload = `id: 41\r\nevent: PAID\r\ndata: ${JSON.stringify(event)}\r\n\r\n`;
    authenticatedFetch.mockResolvedValue(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(bytes.encode(payload.slice(0, 23)));
            controller.enqueue(bytes.encode(payload.slice(23)));
            controller.close();
          },
        }),
      ),
    );
    const received: unknown[] = [];
    const opened = vi.fn();

    await streamTransactionEvents("/api/v1/orders/ord_41/events", {
      signal: new AbortController().signal,
      lastEventId: "40",
      onOpen: opened,
      onEvent: (value) => received.push(value),
    });

    expect(opened).toHaveBeenCalledOnce();
    expect(received).toEqual([event]);
    const init = authenticatedFetch.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).get("Last-Event-ID")).toBe("40");
    expect(new Headers(init.headers).get("Authorization")).toBeNull();
  });

  it("handles CRLF boundaries split between chunks and emits multiple mixed-ending frames", async () => {
    const second = { ...event, eventId: "42", detailCode: "SECOND" };
    const firstJson = JSON.stringify(event);
    const splitJsonAt = firstJson.indexOf('"resourceType"');
    const payload =
      `id: 41\r\ndata: ${firstJson.slice(0, splitJsonAt)}\r\n` +
      `data: ${firstJson.slice(splitJsonAt)}\r\n\r\n` +
      `id: 42\rdata: ${JSON.stringify(second)}\r\r`;
    const bytes = new TextEncoder().encode(payload);
    const firstBoundary = payload.indexOf("\r\n\r\n") + 1;
    authenticatedFetch.mockResolvedValue(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(bytes.slice(0, firstBoundary));
            controller.enqueue(bytes.slice(firstBoundary, firstBoundary + 1));
            controller.enqueue(bytes.slice(firstBoundary + 1));
            controller.close();
          },
        }),
      ),
    );
    const received: unknown[] = [];

    await streamTransactionEvents("/api/v1/orders/ord_41/events", {
      signal: new AbortController().signal,
      onEvent: (value) => received.push(value),
    });

    expect(received).toEqual([event, second]);
  });

  it("flushes decoder bytes and an unterminated tail frame", async () => {
    const tail = {
      ...event,
      eventId: "43",
      detailCode: "支付已确认",
    };
    const bytes = new TextEncoder().encode(`data: ${JSON.stringify(tail)}`);
    const chineseByte = bytes.findIndex((value) => value > 127);
    authenticatedFetch.mockResolvedValue(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(bytes.slice(0, chineseByte + 1));
            controller.enqueue(bytes.slice(chineseByte + 1));
            controller.close();
          },
        }),
      ),
    );
    const received: unknown[] = [];

    await streamTransactionEvents("/api/v1/orders/ord_41/events", {
      signal: new AbortController().signal,
      onEvent: (value) => received.push(value),
    });

    expect(received).toEqual([tail]);
  });

  it("cancels a connection that receives neither events nor heartbeats within the idle budget", async () => {
    vi.useFakeTimers();
    const canceled = vi.fn();
    authenticatedFetch.mockResolvedValue(
      new Response(
        new ReadableStream({
          cancel: canceled,
        }),
      ),
    );

    const streaming = streamTransactionEvents("/api/v1/orders/ord_41/events", {
      signal: new AbortController().signal,
      idleTimeoutMs: 1_000,
      onEvent: vi.fn(),
    });
    const rejected = expect(streaming).rejects.toBeInstanceOf(
      TransactionStreamIdleTimeoutError,
    );
    await vi.advanceTimersByTimeAsync(1_000);

    await rejected;
    expect(canceled).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it("uses bounded exponential reconnect delays", () => {
    expect(transactionReconnectDelay(1)).toBe(2000);
    expect(transactionReconnectDelay(3)).toBe(8000);
    expect(transactionReconnectDelay(20)).toBe(8000);
  });
});
