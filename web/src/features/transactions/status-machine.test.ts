import { describe, expect, it } from "vitest";

import {
  initialTransactionState,
  isDurableEventId,
  isTransactionEvent,
  reduceTransactionEvent,
  type TransactionEvent,
} from "@/features/transactions/status-machine";

function event(
  eventId: string,
  eventType: TransactionEvent["eventType"],
): TransactionEvent {
  return {
    eventId,
    resourceType: "ORDER",
    resourceId: "order-1",
    orderId: "order-1",
    eventType,
    detailCode: eventType,
    occurredAt: "2026-08-08T05:00:00Z",
  };
}

describe("transaction status machine", () => {
  it("deduplicates replayed SSE events by durable event ID", () => {
    const once = reduceTransactionEvent(
      initialTransactionState,
      event("10", "ORDER_CREATED"),
    );
    const replayed = reduceTransactionEvent(once, event("10", "ORDER_CREATED"));
    expect(replayed).toBe(once);
    expect(replayed.events).toHaveLength(1);
  });

  it("ignores an older event in its entirety", () => {
    const paid = reduceTransactionEvent(
      initialTransactionState,
      event("20", "PAID"),
    );
    const delayedPending = reduceTransactionEvent(
      paid,
      event("19", "PENDING_PAYMENT"),
    );
    expect(delayedPending).toBe(paid);
    expect(delayedPending.events.map((item) => item.eventId)).toEqual(["20"]);
  });

  it("allows late success to explain a previously closed order without calling it paid", () => {
    const closed = reduceTransactionEvent(
      initialTransactionState,
      event("30", "CLOSED"),
    );
    const late = reduceTransactionEvent(closed, event("31", "LATE_SUCCEEDED"));
    expect(late.latest?.eventType).toBe("LATE_SUCCEEDED");
  });

  it("keeps the authoritative closed, canceled, then late-success sequence", () => {
    const closed = reduceTransactionEvent(
      initialTransactionState,
      event("30", "CLOSED"),
    );
    const canceled = reduceTransactionEvent(closed, event("31", "CANCELED"));
    const late = reduceTransactionEvent(
      canceled,
      event("32", "LATE_SUCCEEDED"),
    );

    expect(late.events.map((item) => item.eventType)).toEqual([
      "CLOSED",
      "CANCELED",
      "LATE_SUCCEEDED",
    ]);
    expect(late.latest?.eventType).toBe("LATE_SUCCEEDED");
  });

  it.each(["-1", "+1", "1.0", " 1", "01", "", "1e3"])(
    "rejects non-canonical durable event ID %s before BigInt conversion",
    (eventId) => {
      expect(isDurableEventId(eventId)).toBe(false);
      expect(
        reduceTransactionEvent(
          initialTransactionState,
          event(eventId, "ORDER_CREATED"),
        ),
      ).toBe(initialTransactionState);
    },
  );

  it.each(["0", "1", "9007199254740993"])(
    "accepts canonical non-negative decimal event ID %s",
    (eventId) => {
      expect(isDurableEventId(eventId)).toBe(true);
    },
  );

  it.each(["PAID", "COMPENSATED"] as const)(
    "does not let late success overwrite the %s terminal fact",
    (terminalState) => {
      const settled = reduceTransactionEvent(
        initialTransactionState,
        event("40", terminalState),
      );
      const late = reduceTransactionEvent(
        settled,
        event("41", "LATE_SUCCEEDED"),
      );

      expect(late).toBe(settled);
      expect(late.events).toHaveLength(1);
    },
  );

  it("ignores CLOSED#100 followed by an older LATE_SUCCEEDED#99", () => {
    const closed = reduceTransactionEvent(
      initialTransactionState,
      event("100", "CLOSED"),
    );
    const late = reduceTransactionEvent(closed, event("99", "LATE_SUCCEEDED"));

    expect(late).toBe(closed);
    expect(late.events.map((item) => item.eventId)).toEqual(["100"]);
  });

  it("ignores a different event that reuses the latest event ID", () => {
    const created = reduceTransactionEvent(
      initialTransactionState,
      event("50", "ORDER_CREATED"),
    );
    const duplicateId = reduceTransactionEvent(
      created,
      event("50", "PENDING_PAYMENT"),
    );

    expect(duplicateId).toBe(created);
  });

  it("does not record an illegal state transition", () => {
    const reserved = reduceTransactionEvent(
      initialTransactionState,
      event("60", "RESERVED"),
    );
    const paid = reduceTransactionEvent(reserved, event("61", "PAID"));

    expect(paid).toBe(reserved);
    expect(paid.events.map((item) => item.eventType)).toEqual(["RESERVED"]);
  });

  it("accepts a complete transaction event with absent optional identifiers", () => {
    expect(
      isTransactionEvent({
        eventId: "70",
        resourceType: "ORDER",
        resourceId: "order-70",
        eventType: "ORDER_CREATED",
        detailCode: "ORDER_ACCEPTED",
        occurredAt: "2026-08-08T05:00:00Z",
      }),
    ).toBe(true);
  });

  it.each([
    ["blank resourceId", { resourceId: " " }],
    ["blank detailCode", { detailCode: "" }],
    ["blank occurredAt", { occurredAt: "\t" }],
    ["object requestId", { requestId: { value: "request-70" } }],
    ["blank requestId", { requestId: "" }],
    ["blank reservationNo", { reservationNo: " " }],
    ["blank orderId", { orderId: "" }],
  ])("rejects malformed SSE field: %s", (_label, override) => {
    expect(
      isTransactionEvent({
        eventId: "70",
        resourceType: "ORDER",
        resourceId: "order-70",
        eventType: "ORDER_CREATED",
        requestId: "request-70",
        orderId: "order-70",
        detailCode: "ORDER_ACCEPTED",
        occurredAt: "2026-08-08T05:00:00Z",
        ...override,
      }),
    ).toBe(false);
  });
});
