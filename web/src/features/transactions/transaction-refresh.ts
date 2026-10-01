import { findApiProblemError } from "@/api/core/problem";
import { SessionExpiredError } from "@/auth/auth-domain";
import { useEffect, useRef } from "react";

import type { StreamConnection } from "@/features/transactions/use-transaction-stream";

const finalOrders = new Set(["PAID", "SHIPPED", "COMPLETED", "CANCELED"]);
const finalReservations = new Set([
  "ORDER_CREATED",
  "COMPENSATED",
  "EXPIRED",
  "CANCELED",
]);

export function transactionPollingInterval(
  kind: "order" | "reservation",
  status: string | undefined,
  connection: StreamConnection,
  error?: unknown,
): number | false {
  if (connection === "unavailable" || error instanceof SessionExpiredError)
    return false;
  if (error) {
    const status = findApiProblemError(error)?.problem.status;
    // A healthy stream cannot repair a failed snapshot without another event.
    return !status || status >= 500 || status === 408 || status === 429
      ? 10_000
      : false;
  }
  const terminal = kind === "order" ? finalOrders : finalReservations;
  if ((status && terminal.has(status)) || connection === "live") return false;
  return 10_000;
}

/** Coalesce events while a snapshot is loading, then refresh once for the newest fact. */
export function useTransactionRefresh(
  eventId: string | undefined,
  isFetching: boolean,
  refetch: () => Promise<unknown>,
) {
  const appliedEvent = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!eventId || isFetching || appliedEvent.current === eventId) return;
    appliedEvent.current = eventId;
    void refetch();
  }, [eventId, isFetching, refetch]);
}
