export type TransactionEventType =
  | "RESERVED"
  | "ORDER_CREATED"
  | "PENDING_PAYMENT"
  | "PAYMENT_FAILED"
  | "PAID"
  | "CLOSED"
  | "CANCELED"
  | "COMPENSATING"
  | "COMPENSATED"
  | "LATE_SUCCEEDED";

export interface TransactionEvent {
  eventId: string;
  resourceType: "RESERVATION" | "ORDER";
  resourceId: string;
  reservationNo?: string;
  orderId?: string;
  eventType: TransactionEventType;
  requestId?: string;
  detailCode: string;
  occurredAt: string;
}

export interface TransactionState {
  events: TransactionEvent[];
  seenEventIds: ReadonlySet<string>;
  latest?: TransactionEvent;
}

const eventTypes = new Set<TransactionEventType>([
  "RESERVED",
  "ORDER_CREATED",
  "PENDING_PAYMENT",
  "PAYMENT_FAILED",
  "PAID",
  "CLOSED",
  "CANCELED",
  "COMPENSATING",
  "COMPENSATED",
  "LATE_SUCCEEDED",
]);

const legalSuccessors: Record<
  TransactionEventType,
  ReadonlySet<TransactionEventType>
> = {
  RESERVED: new Set(["ORDER_CREATED", "COMPENSATING"]),
  ORDER_CREATED: new Set(["PENDING_PAYMENT", "CLOSED", "CANCELED"]),
  PENDING_PAYMENT: new Set(["PAYMENT_FAILED", "PAID", "CLOSED", "CANCELED"]),
  PAYMENT_FAILED: new Set(),
  PAID: new Set(),
  CLOSED: new Set(["CANCELED", "LATE_SUCCEEDED"]),
  CANCELED: new Set(["LATE_SUCCEEDED"]),
  COMPENSATING: new Set(["COMPENSATED"]),
  COMPENSATED: new Set(),
  LATE_SUCCEEDED: new Set(),
};

export const initialTransactionState: TransactionState = {
  events: [],
  seenEventIds: new Set<string>(),
};

export function isDurableEventId(value: string): boolean {
  return /^(0|[1-9][0-9]*)$/u.test(value);
}

export function reduceTransactionEvent(
  state: TransactionState,
  event: TransactionEvent,
): TransactionState {
  if (
    !isDurableEventId(event.eventId) ||
    state.seenEventIds.has(event.eventId) ||
    (state.latest !== undefined &&
      BigInt(event.eventId) <= BigInt(state.latest.eventId))
  ) {
    return state;
  }

  const latest = state.latest;
  const mayAdvance = latest
    ? legalSuccessors[latest.eventType].has(event.eventType)
    : event.eventType !== "LATE_SUCCEEDED";
  if (!mayAdvance) return state;

  const seenEventIds = new Set(state.seenEventIds);
  seenEventIds.add(event.eventId);
  return {
    events: [...state.events, event],
    seenEventIds,
    latest: event,
  };
}

export function isTransactionEvent(value: unknown): value is TransactionEvent {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  const nonEmptyString = (candidate: unknown): candidate is string =>
    typeof candidate === "string" && candidate.trim().length > 0;
  const optionalNonEmptyString = (candidate: unknown): boolean =>
    candidate === undefined || nonEmptyString(candidate);
  return (
    nonEmptyString(record.eventId) &&
    isDurableEventId(record.eventId) &&
    (record.resourceType === "RESERVATION" ||
      record.resourceType === "ORDER") &&
    nonEmptyString(record.resourceId) &&
    nonEmptyString(record.eventType) &&
    eventTypes.has(record.eventType as TransactionEventType) &&
    optionalNonEmptyString(record.requestId) &&
    optionalNonEmptyString(record.reservationNo) &&
    optionalNonEmptyString(record.orderId) &&
    nonEmptyString(record.detailCode) &&
    nonEmptyString(record.occurredAt)
  );
}
