# Reliable messaging

HotShop uses **at-least-once delivery with idempotent business effects**. MySQL is the source of truth for business and publication state; cross-system delivery can repeat after failures.

## Outbox state and ownership

Business transactions insert `outbox_event` rows in `NEW`. A task instance briefly locks due rows with `FOR UPDATE SKIP LOCKED`, changes them to `PUBLISHING`, assigns a random `lease_token`, advances `version`, and commits before contacting RabbitMQ. Expired leases can be reclaimed. Every result update includes both token and version, so a late confirm from an old publisher cannot overwrite the current owner.

`publish_attempts` counts all broker publication attempts over the event lifetime, including attempts whose result is unknown. `consecutive_attempts` counts only the current automatic retry round. Retryable failures use bounded exponential backoff; after the configured maximum the row transitions to terminal `FAILED`. Automatic claiming never selects `FAILED`. Manual replay increments `manual_replay_count`, resets only `consecutive_attempts`, and preserves cumulative publication history. An expired `PUBLISHING` lease already at the limit is fenced into `FAILED` instead of remaining stuck.

An event becomes `PUBLISHED` only after a correlated ACK and absence of a mandatory return. NACK, return, confirm timeout, and connection failure are failures. A crash after broker ACK but before the MySQL update produces a later duplicate, as expected with at-least-once delivery.

## Inbox and timeout topology

The stable consumer `hotshop-legacy-order-timeout-v1` inserts `(consumer_name,event_id)` in `processed_event` in the same transaction as cancellation, inventory restoration, and the deterministic `ORDER_CANCELED` Outbox row. ACK occurs only after commit. Duplicates therefore have no second business effect.

`LEGACY_ORDER_TIMEOUT_REQUESTED` uses a durable direct schedule exchange and a delay queue whose TTL comes from `HOTSHOP_LEGACY_ORDER_TIMEOUT=15m` (exactly 900,000 ms), plus DLX to the ready queue. Remaining per-message expiration avoids extending a partially elapsed deadline; an already expired event goes directly to the ready exchange. If delivery is early, the consumer atomically records the original Inbox row and a deterministic timeout-reschedule Outbox row before ACK, so it neither hot-requeues nor loses the later timeout opportunity.

The timeout envelope is untrusted input. The consumer rejects unknown fields and verifies schema, UUID event identity, event/aggregate types, aggregate and payload order IDs, User, amount, CNY currency, and expiry against the locked MySQL order. JSON/schema poison and fact conflicts are rejected to the dead-letter queue. Database failures escape without ACK. Database `expires_at <= UTC_TIMESTAMP(6)` remains authoritative.

Only ordinary orders (`reservation_id IS NULL`) are eligible. Flash-sale payment, timeout, Redis qualification release, and terminal-state races remain TASK-10 work.

## FAILED operations

Administrators with `ROLE_ADMIN` list redacted failures at `/admin/api/v1/outbox/failed`, then POST `/admin/api/v1/outbox/{eventId}/replay` with a reason. HTTP only changes MySQL; publication remains asynchronous. Published events cannot be replayed. Every attempt appends an audit record, and no Agent endpoint exposes this operation.
