# HotShop local performance harness

This directory contains the Docker-only k6 harness. The supported entry point is
`script/ci/verify-task20-performance.ps1`; do not run the scenario directly for a formal result,
because the orchestrator owns data sizing, Run ID isolation, evidence capture, reconciliation,
and cleanup. Run the commands below from the repository root with PowerShell 7, Docker and
Compose. The load generator and application runtime execute in containers.

[Project setup](../../README.md) · [Performance evidence](../../docs/quality/task-20-performance.md)

The `target-5k` profile is a measurement target, not an achieved capacity claim. Existing reports
retain their original hardware, versions, time windows and failures; rerun against the current
checkout before making a new performance claim.

## Profiles

| Profile | Work performed |
|---|---|
| `smoke` | Real login, activity query, new reservation, owned-status polling, and async order observation. |
| `baseline` | Read baseline; configurable 100/250/500/1000 RPS new-intent platforms; replay; oversell boundary; 50/30/20 mixed read/new/authenticated-read traffic. |
| `journey-baseline` | Three separate 60-second stages: real password login, ordinary purchase with prepared JWTs, and reservation with prepared JWTs. Defaults to 10 RPS and 32 VUs. |
| `target-5k` | Explicit 5000 RPS, 10-second new-intent platform by default. It is never described as sustained if either rate or duration is missed. |
| `agent-isolation` | FakeModel Agent sessions/messages/runs/SSE completion in parallel with new transaction intents. |

The Agent test measures this repository's orchestration and APIs. It does not call DeepSeek or
Qwen and says nothing about real-model latency. k6 receives SSE as one HTTP response, so
`agent-events-complete-stream` measures complete-stream time, not per-event delivery latency.

## Invocation

```powershell
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile smoke
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile baseline
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile target-5k
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile agent-isolation
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile journey-baseline -Rate 20 -VUs 32 -Duration 60s
```

Use `-Rates 100,250,500,1000`, `-Rate`, `-VUs`, `-Warmup`, `-Duration`, `-DataSeed`,
`-UserCount`, `-Inventory`, `-ActivityId` (zero means run-owned automatic IDs), `-RunId`,
`-BaseUrl`, `-AgentBaseUrl`, and
`-PrometheusRemoteWriteUrl` to parameterize a run.
`-KeepStack` retains only the named run stack. `-RequirePerformanceTarget` converts an otherwise
valid measurement that misses the 5000 RPS target into exit code 2. A correctness, evidence, k6,
or cleanup failure always exits non-zero.

`arrival-rate` schedules iterations independently of VUs; RPS is completed request attempts per
second; VUs are workers needed to sustain that schedule. `dropped_iterations` therefore means k6
could not acquire enough workers. `-VUs` is a hard generator budget
(`preAllocatedVUs=maxVUs`) so an undersized generator produces an explicit dropped-iteration result
instead of exhausting the host. A replay uses the exact same user, body, and idempotency key and
is tagged `intent=replay`; it is never counted as new-intent capacity.

## Separate login from transaction capacity

`journey-baseline` runs each stage sequentially in one isolated stack. Login uses a different
account per iteration and includes password verification and session issuance. Purchase and
reservation authenticate their unique users in k6 setup, outside the measured window. Setup can
take minutes; its cost has not disappeared from a real customer's journey. The two-second
warmup issues catalog reads, not login or purchase requests.

Report `completedWithinWindowRps`, `completedWithinWindow`, `completedAfterWindow`,
`journeyAttempts`, `journeySuccesses`, `droppedIterations`, and P95/P99 together. The compatibility
fields `actualRps` and `newIntentRps` divide all completed attempts, including graceful-stop
work, by the configured duration; they are not sustained throughput. A stage lasting less than
60 seconds cannot populate `highestCredibleSustainedRps`. That field only concerns reservations,
not login. A missed rate with correct orders is a valid measurement, not a capacity pass.

The profile refuses a JWT pool whose identities × VUs exceed 100,000. Ordinary purchases use a
dedicated product. Verification checks remaining stock, order quantity, duplicate users and
published creation/timeout events. Reservations retain the MySQL/Redis, outbox/inbox and
reconciliation checks. Only application dependencies and Prometheus start for this profile.

For a local resource comparison, use the same application image IDs and settings for both runs:

```powershell
$env:HOTSHOP_JAVA_TOOL_OPTIONS = '-XX:TieredStopAtLevel=1 -Xmx256m -XX:ActiveProcessorCount=2'
$env:MYSQL_MEMORY_LIMIT = '768m'
$env:REDIS_CACHE_MEMORY_LIMIT = '64m'
$env:REDIS_SECKILL_MEMORY_LIMIT = '64m'
$env:RABBITMQ_MEMORY_LIMIT = '256m'
$env:PROMETHEUS_MEMORY_LIMIT = '256m'
$env:TASK20_K6_MEMORY_LIMIT = '512m'
$env:TASK20_PORTAL_CPU_LIMIT = '2.0'
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile journey-baseline -Rate 20 -VUs 32 -Duration 60s -RunId run-journey-2cpu
$env:TASK20_PORTAL_CPU_LIMIT = '4.0'
pwsh -NoProfile -File .\script\ci\verify-task20-performance.ps1 -Profile journey-baseline -Rate 20 -VUs 32 -Duration 60s -RunId run-journey-4cpu -SkipBuild
```

Use fresh Run IDs. The variables set above remain in the parent shell; remove them or close
that shell after the comparison. The performance overlay defaults to Portal
2 CPU/640 MiB, Admin 1 CPU/512 MiB and Task 2 CPU/640 MiB. RabbitMQ has 1 CPU and two Erlang
schedulers. `resource-limits.json` records effective container limits and immutable image IDs.
These limits apply only to performance stacks. Keep other workloads stable; a shared Docker
Desktop measurement is not a production capacity claim. See the
[2026-10-03 measurement](../../docs/quality/performance-recovery-2026-10-03.md) for the observed limits.

## Data and secrets

`database/data/load-data.sql` creates deterministic `LOAD-<seed>-...` users, configurable products, and
run-slot activities outside Flyway. New-intent setup refuses to start when the unique-user or
inventory calculation is insufficient. Authentication uses the real login endpoint. Access
tokens live only in k6 setup data, are never printed or persisted, and disappear with the k6
container. Metrics use only `testid`, `profile`, `scenario`, `endpoint`, and `intent`; identifiers
and idempotency keys are not metric labels.

Every run writes ignored evidence under `target/task20-performance/<run-id>/`. The SQL source of
truth for async order latency is:

```sql
TIMESTAMPDIFF(MICROSECOND, sale_reservation.reserved_at, sales_order.created_at) / 1000
```

The report records nearest-rank P50/P95/P99, maximum, and sample count. Status-poll elapsed time is
kept as a client observation only and is not substituted for this persisted timestamp delta.
