import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { mapProblemResponse } from "@/api/core/problem";
import { userAuth } from "@/auth/domains";
import type { TransactionEvent } from "@/features/transactions/status-machine";

const { streamTransactionEvents } = vi.hoisted(() => ({
  streamTransactionEvents: vi.fn(),
}));

vi.mock("@/features/transactions/transaction-stream", () => ({
  streamTransactionEvents,
}));

import {
  useTransactionStream,
  transactionRetryAfterDelay,
  waitForReconnectDelay,
} from "@/features/transactions/use-transaction-stream";

function event(
  eventId: string,
  resourceId: string,
  eventType: TransactionEvent["eventType"] = "ORDER_CREATED",
): TransactionEvent {
  return {
    eventId,
    resourceType: "ORDER",
    resourceId,
    orderId: resourceId,
    eventType,
    detailCode: eventType,
    occurredAt: "2026-08-09T00:00:00Z",
  };
}

describe("useTransactionStream", () => {
  afterEach(() => {
    userAuth.store.getState().clearSession();
  });
  beforeEach(() => {
    streamTransactionEvents.mockReset();
    streamTransactionEvents.mockImplementation(
      (_path: string, options: { signal: AbortSignal }) =>
        new Promise<void>((resolve) => {
          options.signal.addEventListener("abort", () => resolve(), {
            once: true,
          });
        }),
    );
  });

  it("isolates events, Last-Event-ID and reconnect state across A to B to A navigation", async () => {
    const pathA = "/api/v1/orders/A/events";
    const pathB = "/api/v1/orders/B/events";
    const hook = renderHook(({ path }) => useTransactionStream(path), {
      initialProps: { path: pathA },
    });

    await waitFor(() =>
      expect(streamTransactionEvents).toHaveBeenCalledTimes(1),
    );
    const firstAOptions = streamTransactionEvents.mock.calls[0]?.[1] as {
      lastEventId?: string;
      onOpen: () => void;
      onEvent: (value: TransactionEvent) => void;
    };
    expect(firstAOptions.lastEventId).toBeUndefined();
    act(() => firstAOptions.onEvent(event("11", "A")));
    expect(
      hook.result.current.state.events.map((item) => item.resourceId),
    ).toEqual(["A"]);

    hook.rerender({ path: pathB });
    expect(hook.result.current.state.events).toEqual([]);
    await waitFor(() =>
      expect(streamTransactionEvents).toHaveBeenCalledTimes(2),
    );
    const bOptions = streamTransactionEvents.mock.calls[1]?.[1] as {
      lastEventId?: string;
      onEvent: (value: TransactionEvent) => void;
    };
    expect(bOptions.lastEventId).toBeUndefined();

    act(() => {
      firstAOptions.onEvent(event("12", "A"));
      bOptions.onEvent(event("21", "B"));
    });
    expect(
      hook.result.current.state.events.map((item) => item.resourceId),
    ).toEqual(["B"]);

    hook.rerender({ path: pathA });
    expect(hook.result.current.state.events).toEqual([]);
    await waitFor(() =>
      expect(streamTransactionEvents).toHaveBeenCalledTimes(3),
    );
    const secondAOptions = streamTransactionEvents.mock.calls[2]?.[1] as {
      lastEventId?: string;
      onOpen: () => void;
      onEvent: (value: TransactionEvent) => void;
    };
    expect(secondAOptions.lastEventId).toBeUndefined();

    expect(hook.result.current.connection).toBe("connecting");
    act(() => firstAOptions.onOpen());
    expect(hook.result.current.connection).toBe("connecting");

    act(() => {
      firstAOptions.onEvent(event("13", "A"));
      secondAOptions.onOpen();
      secondAOptions.onEvent(event("31", "A"));
    });
    expect(
      hook.result.current.state.events.map((item) => item.eventId),
    ).toEqual(["31"]);
    expect(hook.result.current.connection).toBe("live");
  });

  it("cancels a pending reconnect delay immediately on abort", async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const completed = vi.fn();
    const pending = waitForReconnectDelay(8000, controller.signal).then(
      completed,
    );

    controller.abort();
    await pending;

    expect(completed).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it("reports reconnecting immediately when a live connection fails, before backoff", async () => {
    let rejectConnection: ((error: Error) => void) | undefined;
    streamTransactionEvents
      .mockImplementationOnce(
        (
          _path: string,
          options: { signal: AbortSignal; onOpen: () => void },
        ) => {
          options.onOpen();
          return new Promise<void>((_resolve, reject) => {
            rejectConnection = reject;
          });
        },
      )
      .mockImplementation(
        (_path: string, options: { signal: AbortSignal }) =>
          new Promise<void>((resolve) => {
            options.signal.addEventListener("abort", () => resolve(), {
              once: true,
            });
          }),
      );
    const hook = renderHook(() =>
      useTransactionStream("/api/v1/orders/watchdog/events"),
    );
    await waitFor(() => expect(hook.result.current.connection).toBe("live"));

    act(() => rejectConnection?.(new Error("idle watchdog")));

    await waitFor(() =>
      expect(hook.result.current.connection).toBe("reconnecting"),
    );
    expect(streamTransactionEvents).toHaveBeenCalledTimes(1);
    hook.unmount();
  });
});

it("clears the old timeline and cursor when identity changes on the same URL", async () => {
  streamTransactionEvents.mockReset();
  streamTransactionEvents.mockImplementation(
    (_path: string, options: { signal: AbortSignal }) =>
      new Promise<void>((resolve) =>
        options.signal.addEventListener("abort", () => resolve(), {
          once: true,
        }),
      ),
  );
  const login = (userId: string) =>
    userAuth.store.getState().setSession({
      userId,
      username: userId,
      accessToken: userId,
      expiresAt: "2099-01-01T00:00:00Z",
      role: "ROLE_USER",
    });
  login("account-A");
  const hook = renderHook(() =>
    useTransactionStream("/api/v1/orders/shared/events"),
  );
  await waitFor(() => expect(streamTransactionEvents).toHaveBeenCalled());
  const previous = streamTransactionEvents.mock.calls.at(-1)?.[1] as {
    signal: AbortSignal;
    onEvent: (value: TransactionEvent) => void;
  };
  act(() => previous.onEvent(event("101", "A-private")));
  expect(hook.result.current.state.events).toHaveLength(1);
  const calls = streamTransactionEvents.mock.calls.length;
  act(() => login("account-B"));
  expect(previous.signal.aborted).toBe(true);
  expect(hook.result.current.state.events).toEqual([]);
  await waitFor(() =>
    expect(streamTransactionEvents).toHaveBeenCalledTimes(calls + 1),
  );
  const current = streamTransactionEvents.mock.calls.at(-1)?.[1] as {
    lastEventId?: string;
    onEvent: (value: TransactionEvent) => void;
  };
  expect(current.lastEventId).toBeUndefined();
  act(() => previous.onEvent(event("102", "A-private")));
  expect(hook.result.current.state.events).toEqual([]);
  act(() => current.onEvent(event("201", "B-private")));
  expect(
    hook.result.current.state.events.map((item) => item.resourceId),
  ).toEqual(["B-private"]);
  hook.unmount();
  userAuth.store.getState().clearSession();
});

it("parses Retry-After delta seconds and HTTP dates and ignores malformed values", () => {
  const now = Date.parse("Wed, 30 Sep 2026 15:00:00 GMT");
  expect(transactionRetryAfterDelay("60", now)).toBe(60_000);
  expect(transactionRetryAfterDelay("Wed, 30 Sep 2026 15:01:00 GMT", now)).toBe(
    60_000,
  );
  expect(transactionRetryAfterDelay("Wed, 30 Sep 2026 14:59:00 GMT", now)).toBe(
    0,
  );
  expect(transactionRetryAfterDelay("not-a-date", now)).toBe(0);
  expect(transactionRetryAfterDelay(null, now)).toBe(0);
});

it("does not reconnect before a 503 Retry-After minimum elapses", async () => {
  const error = await mapProblemResponse(
    new Response(JSON.stringify({ detail: "busy", status: 503 }), {
      status: 503,
      headers: {
        "Content-Type": "application/problem+json",
        "Retry-After": "60",
      },
    }),
  );
  streamTransactionEvents.mockReset();
  streamTransactionEvents.mockRejectedValueOnce(error).mockImplementation(
    (_path: string, options: { signal: AbortSignal }) =>
      new Promise<void>((resolve) =>
        options.signal.addEventListener("abort", () => resolve(), {
          once: true,
        }),
      ),
  );
  vi.useFakeTimers();
  const hook = renderHook(() =>
    useTransactionStream("/api/v1/orders/retry-after/events"),
  );
  try {
    await act(() => vi.advanceTimersByTimeAsync(59_999));
    expect(streamTransactionEvents).toHaveBeenCalledTimes(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(streamTransactionEvents).toHaveBeenCalledTimes(2);
  } finally {
    hook.unmount();
    vi.useRealTimers();
  }
});
