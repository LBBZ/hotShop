# TASK-13-RECONCILE-03 verification

Verification date: 2026-08-09. Java verification runs in Docker with Java 21; it does not require a host Java installation. The RECONCILE-02 results retained below are historical baseline evidence only. Every RECONCILE-03 gate and real journey was rerun against the final working tree; the results below are the final acceptance evidence.

## RECONCILE-03 defect-to-test map

The following regression tests were present in, and passed as part of, the final gates recorded below.

### Browser event stream and UI

- `status-machine.test.ts`
  - `ignores an older event in its entirety`;
  - `ignores CLOSED#100 followed by an older LATE_SUCCEEDED#99`;
  - `ignores a different event that reuses the latest event ID`;
  - `does not record an illegal state transition`;
  - dedicated `PAID` -> `LATE_SUCCEEDED` rejection and `COMPENSATED` -> `LATE_SUCCEEDED` rejection cases.
- `use-transaction-stream.test.ts`
  - `isolates events, Last-Event-ID and reconnect state across A to B to A navigation`, including a late callback from the first A generation;
  - `cancels a pending reconnect delay immediately on abort`.
- `transaction-stream.test.ts`
  - `handles CRLF boundaries split between chunks and emits multiple mixed-ending frames`;
  - `flushes decoder bytes and an unterminated tail frame`;
  - existing multiline, multi-frame, heartbeat, split-chunk, and backoff cases.
- `activity-board.test.ts`
  - `distinguishes sold-out inventory from an expired time window`;
  - `treats a live activity whose calibrated countdown ended as expired`.
- The transaction connection status component test must assert that live, reconnecting, and offline text is observable by a status live region without turning the countdown into per-second announcements.

### MySQL V1.7 and timeline writer

- `SchemaConstraintTest.userJourneyTablesEnforceIdempotencyAndTimelineDeduplication` verifies both unique indexes by attempted duplicate writes.
- `SchemaConstraintTest.userJourneyChecksRejectInvalidIdentityStateAndCorrelationData` exercises positive and negative behavior for every V1.7 CHECK, including uppercase SHA-256 input, invalid status/resource type, uppercase `traceparent`, version `ff`, an all-zero trace ID, and an all-zero parent ID.
- `SchemaConstraintTest.userJourneyConstraintNamesAndStreamingIndexOrderAreStable` verifies index column order and `NON_UNIQUE` metadata against the MySQL Testcontainers database.
- `SchemaConstraintTest.schemaContainsNoReferentialConstraints` verifies that the fresh V1.7 schema has no foreign key.
- `TransactionTimelineWriterTest.nullOccurredAtIsRejectedInsteadOfFabricatingCurrentTime` verifies `requireNonNull` behavior.

### OpenAPI runtime semantics

- `PortalApiContractTest.ordinaryOrderFirstCreationReturns201WithoutReplayHeader` verifies the first 201 response, success media type/schema, and absence of replay truth.
- `PortalApiContractTest.ordinaryOrderCreationUsesDurableIdempotencyContract` verifies the 200 replay response, the original request ID, `OrderCreatedResponse`, and boolean `Idempotency-Replayed: true`.
- `PortalApiContractTest.ordinaryOrderChangedFingerprintReturns409ApiProblem` verifies 409, `application/problem+json`, and `ApiProblem`.
- Runtime OpenAPI assertions must additionally inspect the reusable `IdempotencyReplayed` header component and prove its schema is boolean before regenerating the user baseline and TypeScript client.

### Production SSE lifecycle

- `TransactionEventStreamServiceTest.productionExecutorRemovesCompletedConnectionTaskImmediately` uses the production executor configuration and verifies remove-on-cancel queue behavior.
- `TransactionEventStreamServiceTest.emitterFactoryFailureRollsBackCapacityWithoutRegisteringResources` verifies atomic initialization rollback.
- `TransactionEventStreamServiceTest.schedulerRejectionCleansRegisteredEmitterAndRollsBackCapacity` verifies no emitter, task, or connection permit leaks after scheduling rejection.
- Existing completion, timeout, error, ownership, capacity, and shutdown tests remain required.

### `tracestate` continuity and auxiliary-failure isolation

- `OrderServiceTest.ordinaryOrderOutboxCarriesOriginalRequestAndTraceContext` inspects the ordinary-order Outbox payload.
- `FlashSaleReservationIntegrationTest.ordinaryOrderHttpContractDistinguishesCreateReplayAndConflict` inspects the stored request/trace values and ordinary-order Outbox payloads.
- `SeckillOrderReliabilityContainerTest` verifies Flash Sale order/timeout Outbox payload `tracestate`.
- `MockPaymentIntegrationTest.traceStateCrossesPaymentActionCallbackAndResultOutboxes` inspects payment action, callback request, and payment-result Outbox payloads.
- `OutboxPublisherTest.persistedTraceStateCrossesTheFinalRabbitMessageBoundary` inspects the final RabbitMQ message header.
- `ReservationStreamConsumerObservationTest.tracerAndTimerFailuresDoNotBlockConsumptionAndMdcIsRestored` and `OutboxPublisherTest.tracerAndMetricFailuresDoNotBlockPublishAndMdcIsRestored` prove that observation construction failure neither blocks business work nor leaks the worker context.

## RECONCILE-03 final-gate evidence

Status: **passed against the final RECONCILE-03 working tree**.

| Gate | Exact command/evidence | Final result |
|---|---|---|
| Patch integrity | `git diff --check` plus static scans | passed; no diff errors, forbidden E2E constructs, foreign keys, token leakage, or high-cardinality production labels |
| Java 21 reactor | Docker Java 21: `./mvnw -B clean verify` | **BUILD SUCCESS: 254 passed**, 0 failures, 0 errors, 0 skipped; common 12, domain 16, database 35, security 36, task 64, portal 70, admin 21 |
| Python | pytest, Ruff, strict mypy | passed: pytest **116**, Ruff passed, strict mypy passed for **20 source files** |
| Web | `pnpm check` | passed: Vitest **13 files / 55 tests**, production build **2581 modules**, formatting, ESLint, TypeScript, and all three drift checks passed |
| OpenAPI | runtime export, user baseline/client generation, public/user/admin compatibility and drift | passed; runtime 201/200/409 semantics and the reusable boolean replay header were verified, baseline/client regenerated, all three drift checks passed |
| Fresh schema | fresh MySQL Testcontainers schema migrated through V1.7 before behavioral constraint tests | passed in the clean Java reactor; the V1.7 checks, both unique indexes, index metadata/order, and no-foreign-key invariant were exercised against MySQL |
| Real journey | `pnpm exec playwright test e2e/user-transaction-real.spec.ts --reporter=line` | **14 passed, 0 failed, 2 intentionally skipped** |
| Full browser suite | `pnpm exec playwright test --reporter=line` | **20 passed, 0 failed, 2 intentionally skipped** |
| Observability | existing observability/trace verification script | passed: metrics 7, Loki streams 1, dashboards 4, restart recovery true, sentinel leak false |

### Runtime and boundary evidence

- Ordinary-order contract tests proved first creation returns `201`, same-key replay returns `200` with the original request ID and boolean `Idempotency-Replayed: true`, and a changed fingerprint returns `409 application/problem+json` with `ApiProblem`.
- During the real Redis outage, the browser captured exactly `[503, 503, 503]`. Every response used `application/problem+json`, satisfied the required `ApiProblem` shape, and produced the matching visible UI error before the same purchase intent recovered.
- During the Portal restart, the browser observed the old stream fail and the UI leave `live`, recorded a new `/events` request carrying the pre-restart `Last-Event-ID`, and observed the replacement connection return to `live`.
- The Java regression suite directly inspected `outbox_event.payload` and the final RabbitMQ message. It proved that `tracestate` crosses ordinary-order, Flash Sale/timeout, payment-callback, payment-result, and final publisher boundaries; tracer, timer, span, and metric-construction failures did not block business processing or leak MDC/trace context.
- The final observability smoke produced request ID `task11-d6e46fc654714a5fb90709c06ce6df59`, trace ID `2871eaab06c5da99ca1ecf839af44a07`, reservation `rsv_260ab540860c462fb186b832540478b2`, order `ord_bf9dac73aaa3f636084f4971aef87bfe`, and payment `MOCK_648553ac7e784b0faef1f3a9194c98e`.

The real journey must remain free of `page.route`, `route.fulfill`, direct provider callback calls, `createHmac`, test-side signatures, and fixed waits. Its Redis and restart assertions must come from actual browser responses/connections, not database-only inference or the mocked `smoke.spec.ts` suite.

## Historical RECONCILE-02 completed gates

## Completed gates

### Java 21 reactor

The full repository Maven gate completed in the Java 21 container:

```text
./mvnw clean verify
```

Result: **244/244 tests passed** across the reactor.

This run includes the transaction-timeline writer semantics, V1.7 schema constraints, authentication cookie migration, transaction rate limiting, order idempotency, ownership isolation, Redis failure handling, durable SSE recovery, and task/payment processing tests.

### Python agent

The Python gate ran in an isolated Python container with bytecode and tool caches redirected away from the Windows workspace.

- pytest: **116 passed**;
- Ruff: passed;
- strict mypy: passed for **20 source files**.

### Web and runtime OpenAPI

The rebuilt Portal and Administrator applications exported the public, user, admin, and mock-provider-callback runtime baselines. Runtime assertions confirmed the public activity path and its 14 required fields, and confirmed that `POST /api/v1/orders` exposes 201 create, 200 replay, and 409 conflict semantics. Both successful responses use `OrderCreatedResponse`; the replay response requires a boolean `Idempotency-Replayed` header. The order response has four required fields and the timeline response has nine required fields.

Public, user, and admin compatibility checks passed. TypeScript client regeneration and all three drift checks passed. The final web check then completed against the regenerated clients:

- formatting: passed;
- ESLint: passed;
- TypeScript: passed;
- Vitest: **11 files, 42 tests passed**;
- production build: passed, **2580 modules transformed**;
- generated-client drift: public, user, and admin passed;
- Playwright discovery: **22 cases across Chromium desktop and Pixel 7** compiled successfully.

### Compose syntax and static checks

- Root Compose configuration: passed.
- Web Compose configuration: passed.
- `git diff --check`: passed; only line-ending notices were emitted.
- V1.7 scan found no `FOREIGN KEY` or `REFERENCES` clauses.
- Browser-test scan found no `page.route`, `route.fulfill`, direct provider callback, embedded HMAC secret, or fixed-time sleep in the real purchase journey.
- Token-storage scan found no production persistence of access or refresh tokens in `localStorage` or `sessionStorage`.
- Production metric-label scan found no request ID, trace ID, user ID, reservation number, or order ID used as a high-cardinality metric label.

## Transaction timeline and SSE evidence

`user_transaction_timeline` is an append-only **first authoritative business-fact receipt**, not an unlimited audit log. State-changing services write their matching receipt when the authoritative state is committed. The writer uses a normal insert and suppresses only an explicitly verified duplicate of the same owned resource/event fact; unrelated constraint or data-integrity failures propagate.

Order detail, timeline GET, and SSE reads are strictly read-only. They perform ownership checks and select committed rows; they do not call synchronization, perform reconciliation, or add timeline rows. Tests cover unchanged row counts during GET/SSE reads and transaction rollback of timeline facts with their business state.

Flash Sale admission writes `requestId`, `traceparent`, and `tracestate` atomically into the accepted reservation's Redis Stream entry. The worker restores context from that payload. There is no post-Stream request-ID side key, so an observability write cannot race with or overwrite the reservation result, and equal client request IDs from different Users cannot merge trace context.

SSE recovery uses monotonic MySQL event IDs and `Last-Event-ID`, not an in-memory replay buffer. The browser validates event IDs as canonical non-negative decimal integers, deduplicates replayed events, resets all stream state when the resource path changes, and rejects illegal terminal-state regression. The server polls every 2 seconds with an eight-thread scheduler, caps each Portal instance at 200 concurrent streams, sends 15-second heartbeats, expires connections after 30 minutes, and releases tasks and permits on completion, timeout, error, abort, and shutdown.

## Authentication and ownership evidence

User and Administrator sessions use different cookie names, endpoints, and paths. Refresh cookies remain HttpOnly and auth-endpoint scoped. Readable CSRF cookies use `/` for User pages and `/admin` for Administrator pages. Login, refresh, and logout also expire the legacy CSRF cookie paths `/api/v1/auth` and `/admin/api/v1/auth`. No Vite cookie-path rewrite is used.

Access tokens remain memory-only and are sent to SSE in the `Authorization` header, never in URLs. Order, reservation, timeline, payment, and stream lookups include the authenticated User ID; unknown and other-User resources share the same 404 response semantics.

## Fresh Compose and real Playwright

The isolated `hotshop-task13-r03-final` project used independent images, ports, and newly created MySQL, Redis, and RabbitMQ volumes. The deterministic UTF-8 seed was copied into the MySQL container and imported there with `--default-character-set=utf8mb4`; it was not piped through Windows text decoding. The final real journey completed in about 5.3 minutes with **14 passed and 2 intentionally skipped**; the subsequent full browser suite completed with **20 passed and 2 intentionally skipped**. The two real-journey skips are destructive Redis availability and 60-second Access Token expiry proofs that run once on desktop; their desktop cases passed. No real journey test uses HTTP interception, direct callback invocation, an embedded signing secret, or a fixed sleep.

The executed matrix covered:

- anonymous UTF-8 Chinese catalog, search, product details, registration, login, keyboard navigation, and desktop/Pixel 7 layouts;
- refresh-page recovery followed by Access Token expiry, cookie/CSRF refresh, and continued protected access;
- ordinary order payment success, failure, delay, duplicate delivery, and payment-versus-timeout race;
- Flash Sale double click, recoverable retry with the same `Idempotency-Key`, asynchronous order creation, expiry, sold-out, and transaction-originated 429;
- offline feedback, SSE reconnection with `Last-Event-ID`, refresh recovery, Portal restart recovery, and cross-User denial;
- proof that duplicate delivery traverses Outbox -> RabbitMQ -> Task -> callback multiple times while producing one `PAID` fact;
- one final smoke path: login -> Flash Sale -> Redis Stream -> Task -> MySQL -> Outbox -> RabbitMQ -> Mock Payment -> timeline/SSE terminal state.

The browser run initially exposed two real test-environment/client defects before the final pass: a 5-second delayed callback was racing a 5-second close window, so the isolated close window was corrected to 10 seconds; and generated-client `FetchError` wrapping hid the underlying 503 `ApiProblemError`, so the UI now safely unwraps the typed cause and preserves the same purchase-intent key across retries. The final Redis outage case proves three recoverable 503 attempts with one key, recovery to 202, and an explicit replay with the same key, replay header, and reservation URL.

## TASK-11 observability and final smoke

The final RECONCILE-03 run of `script/verify-observability.ps1` passed against the independent app, agent, and observability stack. It verified seven metric families, one matching Loki stream, four dashboards, Tempo continuity across Portal, Task, and Agent, observability-stack restart recovery, and absence of the sentinel secret.

The real final smoke produced:

- request ID: `task11-d6e46fc654714a5fb90709c06ce6df59`;
- trace ID: `2871eaab06c5da99ca1ecf839af44a07`;
- reservation: `rsv_260ab540860c462fb186b832540478b2`;
- order: `ord_bf9dac73aaa3f636084f4971aef87bfe`;
- payment: `MOCK_648553ac7e784b0faef1f3a9194c98e`.

Redis observation tests additionally prove that auxiliary failures cannot change `INTERNAL_STATE_INVALID`, `ACCEPTED`, `IDEMPOTENT_REPLAY`, or an original business exception. Production metrics remain low-cardinality and logs remain structured and redacted.

## Final acceptance rule

All required TASK-13-RECONCILE-03 gates recorded above passed. The workspace remains uncommitted for the primary acceptance task.
