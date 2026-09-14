# TASK-20 local performance harness

This directory contains the Docker-only k6 harness. The supported entry point is
`script/verify-task20-performance.ps1`; do not run the scenario directly for a formal result,
because the orchestrator owns data sizing, Run ID isolation, evidence capture, reconciliation,
and cleanup.

## Profiles

| Profile | Work performed |
|---|---|
| `smoke` | Real login, activity query, new reservation, owned-status polling, and async order observation. |
| `baseline` | Read baseline; configurable 100/250/500/1000 RPS new-intent platforms; replay; oversell boundary; 50/30/20 mixed read/new/authenticated-read traffic. |
| `target-5k` | Explicit 5000 RPS, 10-second new-intent platform by default. It is never described as sustained if either rate or duration is missed. |
| `agent-isolation` | FakeModel Agent sessions/messages/runs/SSE completion in parallel with new transaction intents. |

The Agent test measures this repository's orchestration and APIs. It does not call DeepSeek or
Qwen and says nothing about real-model latency. k6 receives SSE as one HTTP response, so
`agent-events-complete-stream` measures complete-stream time, not per-event delivery latency.

## Invocation

```powershell
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile smoke
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile baseline
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile target-5k
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile agent-isolation
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

## Data and secrets

`database/data/load-data.sql` creates deterministic `LOAD-<seed>-...` users, one product, and
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
