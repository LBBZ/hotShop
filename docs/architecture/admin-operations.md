# Admin operations workspace

TASK-14 turns the Administrator boundary into an incident-oriented operations workspace. All displayed facts come from the Admin process and MySQL; React does not synthesize catalog, transaction, messaging, reconciliation, or audit facts.

## Identity and capability boundary

- `/admin/api/v1/**` is accepted only for an `Administrator Access` token with the Administrator issuer/audience. Anonymous, User Access, and Agent Delegation tokens are rejected by the Spring Security filter chain before controllers run.
- Method authorization remains on management controllers. The React protected route and hidden/disabled controls are usability measures, not authorization.
- Administrator Access Tokens remain in the isolated Zustand in-memory store. The Administrator refresh cookie is HttpOnly and remains separate from the User identity domain.
- No `/agent/api/v1/**` route exposes replay, compensation, refund, account status, permission, or other high-risk operations.

## Fact sources and bounded queries

`GET /admin/api/v1/operations/overview` accepts a 1–168 hour window (24 hours by default). Every count states its exact `[rangeFrom, rangeTo)` and source. Product, Activity, Order, Reservation, and Payment counts use `created_at`; terminal FAILED Outbox counts use `updated_at`; reconciliation findings use `last_seen_at`; human-review processing facts use `updated_at`. The overview therefore performs no unbounded lifetime scan.

Detailed investigation remains server-paged:

| Fact | Endpoint | Stable order |
| --- | --- | --- |
| Catalog Products | `/admin/api/v1/products` | `product_id ASC` |
| Flash Sale Activities | `/admin/api/v1/flash-sales` | `activity_id DESC` |
| Orders | `/admin/api/v1/orders` | `created_at DESC, order_id DESC` |
| Payments | `/admin/api/v1/operations/payments` | `created_at DESC, payment_id DESC` |
| Reconciliation findings | `/admin/api/v1/operations/reconciliation-issues` | `last_seen_at DESC, issue_id DESC` |
| Human-review processing | `/admin/api/v1/operations/manual-reviews` | `updated_at DESC, processing_id DESC` |
| FAILED Outbox | `/admin/api/v1/outbox/failed` | `outbox_id DESC` |
| Audit | `/admin/api/v1/audit-logs` | `occurred_at DESC, audit_id DESC` |

Admin cursors are URL-safe payloads authenticated with HMAC-SHA-256. The signature covers the cursor type, sort tuple, and a digest of every filter. Editing the tuple, reusing a cursor under different filters, or copying it to another list returns `CURSOR_INVALID`. `HOTSHOP_ADMIN_CURSOR_SECRET` may provide a stable key of at least 32 bytes; when absent, the process creates an ephemeral key, intentionally invalidating cursors across restart without writing a secret to the repository.

Order and Payment investigation require an explicit range no larger than 31 days. All filters use parameterized SQL in the dedicated `domain.adminops` repository; existing shared business Mappers are unchanged.

## Mutation and audit semantics

Catalog create, replace, soft-delete, Activity load, and Outbox replay all require a 3–256 character operator reason with at least three non-whitespace characters and no line breaks. Backend validation covers exact decimal money, non-negative inventory, ID formats, activity facts, event state, and allowed state enums. Admin product replacement/deletion locks the active row before the shared mutation, so concurrent deletion cannot be reported as a false success. The append-only audit record contains Administrator identity, action/resource, accurate success/failure, request ID, trace ID, and a minimized state summary including the reason. It never stores request authorization, credentials, raw Outbox payload/error, or full product description.

Outbox replay is the existing safe state transition only: a locked `FAILED` row becomes `NEW`, cumulative attempts remain, the current retry streak resets, and publication stays asynchronous in `task`. A non-FAILED or missing resource produces accurate Problem Details and a failure audit. React additionally requires a second confirmation showing event ID and likely impact, but the backend remains authoritative.

## Reconciliation truthfulness

The workspace reads persisted `seckill_reconciliation_issue`, `seckill_event_processing`, and checkpoint facts. A finding is labelled as evidence requiring investigation; it is never described as repaired. Because the existing Task process does not persist each run's `dryRun`/`autoRepair` mode or report, Admin reports those flags only when the same deployment explicitly supplies them to Admin; otherwise it displays the mode as unknown while showing the persisted findings and checkpoint. Unknown mode never becomes a claim that repair occurred. TASK-14 adds no compensation or repair path.

## Trace links and metrics

The UI accepts a Trace link only for a non-zero, 32-character lowercase hexadecimal trace ID. `VITE_ADMIN_TRACE_URL` is a build-time Tempo/Grafana base URL; only credential-free `http`/`https` URLs are accepted, and the trace ID is added through `URL.searchParams`. Row data cannot supply or replace the destination URL. The local fallback is `http://localhost:3000/explore`.

Operational metrics remain low-cardinality. Application/environment and bounded result/status dimensions are suitable labels; request ID, trace ID, Order ID, Reservation number, Payment number, event ID, and user ID stay in logs, traces, and query results rather than Prometheus labels.
