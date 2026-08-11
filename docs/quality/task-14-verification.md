# TASK-14 verification

Date: 2026-08-09
Baseline: `330fbf6`
Reconciled: 2026-08-10 (`TASK-14-RECONCILE-01`)

## Scope

Verification covers the Administrator identity boundary, signed cursor pagination, bounded operations queries, validated/audited management writes, truthful reconciliation presentation, safe Trace links, React management workflows, Admin-only OpenAPI generation, and the real Compose Administrator journey.

The requested `docs/architecture/audit-log.md` did not exist at the baseline. Audit invariants were verified against `MASTER_PLAN.md`, `database-schema.md`, the append-only audit migration/writer, and existing Admin audit query implementation.

## Results

| Gate                                   | Command                                                                                                                                                | Result                                                                                                                                                                        |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Java 21 Admin package                  | Docker Java 21, `mvn -B -pl admin -am -DskipTests install`                                                                                             | Passed; seven-module dependency reactor and executable Admin jar built successfully                                                                                           |
| Admin tests and integration tests      | Docker Java 21, `mvn -B -pl admin test` with Testcontainers Docker socket                                                                              | Passed: **40 tests**, 0 failures, 0 errors, including real MySQL/Redis integration                                                                                            |
| Web format/lint/typecheck/Vitest/build | `pnpm format:check`, `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build`                                                                          | Passed: **17 files / 79 tests**, ESLint and TypeScript clean, production build **1886 modules**                                                                               |
| Admin OpenAPI runtime/baseline         | Packaged Admin runtime `/v3/api-docs/admin`, canonicalizer, Admin-only copy                                                                            | Passed; only `/admin/api/v1/**` paths accepted; duplicate activity tag found and corrected before baseline update                                                             |
| Admin generated-client drift           | Docker OpenAPI Generator `pnpm api:generate:admin`, `pnpm api:check:admin`                                                                             | Passed; only `web/src/api/generated/admin` was cleared/generated; generated tree matches baseline                                                                             |
| Real Compose Administrator journey     | `pnpm exec playwright test e2e/admin-operations-real.spec.ts --project=chromium --project=mobile-chromium` against isolated `hotshop-task14-reconcile` | Passed: **2 tests** (Chromium desktop and Pixel 7/mobile Chromium), including a real 25-order cursor page transition with no `CURSOR_INVALID`; no management API interception |
| Whitespace                             | `git diff --check`                                                                                                                                     | Passed; only existing LF/CRLF conversion notices                                                                                                                              |

## Test intent

- Real Security filter chain rejects anonymous, User Access, and Agent Delegation tokens at operations, audit, and Outbox routes.
- Real MySQL integration covers the bounded overview, stable payment pagination, HMAC tamper rejection, cross-filter cursor rejection, reconciliation findings, human-review facts, and dry-run/auto-repair wording.
- Validation covers product money/inventory, payment time range, list enum/filter constraints, required management reasons, and Outbox state conflicts.
- Success and failure audit tests verify transaction boundaries and minimized summaries.
- React tests cover Administrator login identity, loading/error/empty states, the Outbox second-confirmation workflow, and stable order query time bounds after a fake-clock advance and cursor transition.
- Trace-link unit tests cover lowercase/non-zero validation, fixed destination, URL encoding, unsupported schemes, credentials, and malformed input.
- The Compose Playwright journey uses real Admin HTTP APIs: login -> inspect issues -> open a Trace -> replay a seeded FAILED Outbox with a reason -> find the success audit -> select 25 seeded orders -> click “加载下一页” -> verify the second page without `CURSOR_INVALID`. It contains no `page.route`, `route.fulfill`, or fixed sleep.

## Runtime findings resolved during verification

- Order and payment query time bounds are now caller-owned query-session state. Cursor requests reuse byte-for-byte equivalent `createdFrom`/`createdTo` values; changing the order status resets both the cursor and time range.
- The Vite proxy is scoped to `/admin/api`, so the SPA route `/admin/login` is not accidentally forwarded to the backend.
- Mobile navigation links retain explicit accessible names even when their visual labels collapse to icons.
- Admin Actuator health no longer probes RabbitMQ when Rabbit support is disabled, so the Compose health check reflects the dependencies that Admin actually uses.
- Reconciliation mode is not inferred from Admin's local defaults. The existing Task service does not persist a per-run mode/report, so Admin returns mode as unknown unless the deployment explicitly shares it; persisted findings and checkpoints remain visible, and the UI never claims a repair.
- Admin Product replacement and soft-delete lock the active row before mutation, preventing a concurrent delete from being audited as a false success.

## Remaining operational constraints

- A stable `HOTSHOP_ADMIN_CURSOR_SECRET` of at least 32 bytes should be supplied for multi-replica or restart-stable pagination. The development fallback is an ephemeral per-process key, which intentionally invalidates old cursors after restart.
- The existing reconciliation schema persists findings and checkpoints, not full checked/findings/repairs run reports. Showing a proven latest run report requires a future Task-owned persistence contract and migration; TASK-14 reports this absence explicitly rather than manufacturing data.
