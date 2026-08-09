# User transaction journey

TASK-13 treats the UI as a projection of server facts, never as the order state authority.

## Purchase intent and idempotency

The browser creates one key when the User explicitly starts a purchase intent. Double clicks and recoverable 429/5xx retries reuse it. Ordinary orders persist a SHA-256 key hash, a stable request fingerprint, the order ID, and request ID in `order_purchase_intent`; Flash Sale reservations retain the existing atomic Redis/Lua contract. Refresh recovery reads order/reservation facts from the server. Access tokens remain only in the in-memory auth store.

## Durable first-fact receipt

`user_transaction_timeline` is an append-only receipt of the first authoritative occurrence of each user-facing business fact. It is not an unlimited audit log of every retry or repeated status assignment. A monotonic auto-increment event ID provides the recovery cursor, while the resource/event uniqueness key preserves the first receipt. State-changing services append the matching event with the original `occurredAt`, `requestId`, `traceparent`, and `tracestate` in the same MySQL transaction whenever the state itself is stored:

- ordinary order creation writes `ORDER_CREATED`;
- payment creation writes `PENDING_PAYMENT`;
- provider callback processing writes `PAYMENT_FAILED`, `PAID`, or `LATE_SUCCEEDED`;
- the seckill worker writes `RESERVED`, `ORDER_CREATED`, `COMPENSATING`, and `COMPENSATED` while projecting the Redis stream into MySQL;
- timeout processing writes `CLOSED` and `CANCELED`.

The writer performs a normal `INSERT`. It requires the authoritative `occurredAt`; a missing timestamp is a programming error and is never replaced with the repair execution time or `Instant.now()`. It treats a duplicate as idempotent only after confirming that the expected resource/event fact already exists with the same ownership and resource links; all other integrity failures propagate and roll back the business transaction.

V1.7 enforces the transaction-fact invariants in MySQL itself. SHA-256 key hashes and request fingerprints accept exactly 64 lowercase hexadecimal characters. `traceparent` validation is case-sensitive, rejects version `ff`, and rejects all-zero trace IDs and parent IDs. Status/resource-type membership, ownership, processing/order linkage, resource identity, correlation formats, both idempotency uniqueness rules, and the ordered streaming index are verified against a fresh MySQL schema. The migration contains no database foreign keys.

Order detail, timeline GET, and SSE are strictly read-only. They perform ownership checks and select already committed receipts; they never synthesize an event, invoke reconciliation, or mutate business tables. Repair is deliberately absent from public, User, Administrator, and Agent APIs. If an operational repair tool is introduced later, it must be an explicit internal workflow with source timestamps and provenance, not a side effect of reading.

Flash Sale intake atomically appends `requestId`, `traceparent`, and `tracestate` to the same Redis Stream entry as the accepted reservation. The worker restores context directly from that entry. There is no post-Stream request-ID side key, so Redis failure after a successful reservation cannot mask `ACCEPTED`, and identical client request IDs from different Users cannot join traces.

SSE incrementally polls the durable timeline every 2 seconds, sends the database event ID as SSE `id`, accepts `Last-Event-ID`, and emits a heartbeat every 15 seconds. There is no in-memory replay buffer. One Portal instance permits at most 200 streams and owns an explicit eight-thread `ScheduledThreadPoolExecutor` with `setRemoveOnCancelPolicy(true)`. A stream expires after 30 minutes. Completion, timeout, error, client abort, or application shutdown cancels its task, removes that task from the production executor queue, and returns its capacity permit. If emitter creation or task scheduling fails at any point during `open`, the initialization transaction unwinds all previously registered callbacks/resources and the active-connection count.

The browser fetches the stream with `Authorization` in a header. Its reducer parses canonical non-negative decimal IDs as `BigInt` and accepts an event only when its ID is strictly greater than the latest accepted event. An equal/older ID or an illegal transition is ignored in its entirety: it changes neither `latest` nor `events`. `LATE_SUCCEEDED` is legal only after `CLOSED` or `CANCELED`; it cannot overwrite `PAID`, `COMPENSATED`, or another incompatible terminal fact.

Every browser connection attempt has a monotonically increasing generation. `onOpen`, `onEvent`, and reconnect dispatches must match that generation, remain active, and have a non-aborted signal. This prevents a delayed callback from an earlier A connection from corrupting state after A -> B -> A navigation. The SSE decoder recognizes CR, LF, and CRLF delimiters even when CR/LF or multibyte text crosses network chunks, flushes the `TextDecoder` at EOF, and emits a valid unterminated tail frame.

The browser listens for online/offline transitions, aborts a stranded fetch while offline, reconnects with bounded exponential backoff, and sends its last durable event ID in the header. A successfully opened stream marks the connection live even when no newer business event exists. Live, reconnecting, and offline changes are announced through a restrained status live region. Activity controls distinguish `SOLD_OUT` ("已售罄") from `EXPIRED` ("已过期") in both button text and disabled explanation.

## Trace continuity and failure isolation

`tracestate` travels with `requestId` and `traceparent` through ordinary order creation, Flash Sale order/timeout Outbox payloads, payment callback requests, payment-result Outbox payloads, and the headers of the final RabbitMQ message. Consumers restore the saved context before business work and restore the caller's MDC/trace context afterward.

Tracing remains auxiliary. A tracer, timer, span, or context-propagation failure in `ReservationStreamConsumer` or `OutboxPublisher` is recorded with low-cardinality diagnostics but cannot prevent the reservation from being consumed or a claimed Outbox event from being published. Tests inspect both the persisted `outbox_event.payload` and the final broker message headers rather than inferring continuity from application logs.

## Authentication recovery

User and Administrator sessions use separate cookie names and endpoints. User refresh cookies are HttpOnly and scoped to `/api/v1/auth`; Administrator refresh cookies are HttpOnly and scoped to `/admin/api/v1/auth`. The readable CSRF cookies use `/` and `/admin` respectively so their own business pages can read them. Login, refresh rotation, logout, and cookie clearing use the same current per-domain paths and additionally expire the legacy CSRF paths (`/api/v1/auth` and `/admin/api/v1/auth`) during migration. Access tokens remain memory-only and are never placed in storage, URLs, logs, or SSE payloads.

## Ownership and payment

Order, reservation, timeline, payment, and SSE lookups always include the authenticated User ID. Unknown and other-User resources deliberately share 404 semantics. Mock checkout displays “模拟支付，不会产生真实扣款”. The UI waits for the order/timeline fact and never marks an order PAID from a button acknowledgement.

## Presentation

The visual system keeps HotShop's cool navy/cool-grey surface, transaction red signal, teal success, amber warning, Barlow Condensed display type, Manrope body, and IBM Plex Mono facts. Desktop uses a commerce board plus receipt composition; mobile collapses to one readable column. Focus rings, semantic labels, `aria-live`, reduced-motion rules, and text/icon status labels are first-class.

## Real-browser acceptance evidence

The real user journey is run separately from the mocked smoke suite. During Redis unavailability it must observe exactly three transaction responses `[503, 503, 503]`, validate `application/problem+json` as `ApiProblem`, show the recoverable message, and prove that recovery reuses the same purchase-intent key. During a Portal restart the browser must observe the old fetch closing, issue a new `/events` request with the last accepted durable ID in `Last-Event-ID`, and return to the live state before accepting the terminal fact.

These proofs use the real Compose Outbox -> RabbitMQ -> Task -> callback -> timeline/SSE path. They do not use route interception, test-side provider callbacks or signatures, HMAC construction, fixed waits, or mocked-smoke results as substitutes.
