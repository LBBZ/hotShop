import { createElement, type PropsWithChildren } from "react";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  transactionPollingInterval,
  useTransactionRefresh,
} from "@/features/transactions/transaction-refresh";

describe("transaction snapshot fallback", () => {
  it("polls only while a nonterminal snapshot has no live stream", () => {
    expect(transactionPollingInterval("order", "PENDING", "live")).toBe(false);
    expect(transactionPollingInterval("order", "PENDING", "reconnecting")).toBe(
      10_000,
    );
    for (const status of ["PAID", "SHIPPED", "COMPLETED", "CANCELED"])
      expect(transactionPollingInterval("order", status, "offline")).toBe(
        false,
      );
    expect(
      transactionPollingInterval("reservation", "RESERVED", "reconnecting"),
    ).toBe(10_000);
    for (const status of [
      "ORDER_CREATED",
      "COMPENSATED",
      "EXPIRED",
      "CANCELED",
    ])
      expect(transactionPollingInterval("reservation", status, "offline")).toBe(
        false,
      );
  });
  it("coalesces facts received during a query and fetches after that query completes", async () => {
    const refetch = vi.fn(() => Promise.resolve());
    const hook = renderHook(
      ({ eventId, fetching }) =>
        useTransactionRefresh(eventId, fetching, refetch),
      { initialProps: { eventId: "1", fetching: true } },
    );
    hook.rerender({ eventId: "2", fetching: true });
    expect(refetch).not.toHaveBeenCalled();
    hook.rerender({ eventId: "2", fetching: false });
    await waitFor(() => expect(refetch).toHaveBeenCalledOnce());
    act(() => hook.rerender({ eventId: "2", fetching: false }));
    expect(refetch).toHaveBeenCalledOnce();
  });
});

it("recovers a failed terminal snapshot while SSE remains live, then stops polling", async () => {
  vi.useFakeTimers();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const fetchSnapshot = vi
    .fn<() => Promise<{ status: string }>>()
    .mockRejectedValueOnce(new Error("503 temporary outage"))
    .mockResolvedValue({ status: "PAID" });
  const wrapper = ({ children }: PropsWithChildren) =>
    createElement(QueryClientProvider, { client }, children);
  const hook = renderHook(
    () => {
      const query = useQuery({
        queryKey: ["snapshot-recovery"],
        queryFn: fetchSnapshot,
        initialData: { status: "PENDING" },
        staleTime: Infinity,
        refetchInterval: (snapshot) =>
          transactionPollingInterval(
            "order",
            snapshot.state.data?.status,
            "live",
            snapshot.state.error,
          ),
      });
      useTransactionRefresh("paid-event", query.isFetching, query.refetch);
      return query;
    },
    { wrapper },
  );
  try {
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(hook.result.current.isError).toBe(true);
    expect(fetchSnapshot).toHaveBeenCalledTimes(1);
    await act(() => vi.advanceTimersByTimeAsync(10_001));
    expect(hook.result.current.data?.status).toBe("PAID");
    expect(hook.result.current.isError).toBe(false);
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(fetchSnapshot).toHaveBeenCalledTimes(2);
  } finally {
    hook.unmount();
    client.clear();
    vi.useRealTimers();
  }
});
