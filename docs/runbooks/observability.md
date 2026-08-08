# Local observability runbook

## Start and inspect

Generate local auth keys once, then start the application and telemetry profiles:

```powershell
.\script\generate-auth-keys.ps1
docker compose --env-file .env.example --profile app --profile agent --profile observability up -d --build
```

Endpoints:

- Grafana: <http://localhost:3000>
- Prometheus: <http://localhost:9090>
- Loki readiness/API: <http://localhost:3100/ready>
- Tempo API: <http://localhost:3200/ready>
- Alloy UI/readiness: <http://localhost:12345>

The checked-in Grafana password is a public local placeholder. Override it outside Git on a shared
machine. Anonymous access is Viewer-only; provisioning files remain the configuration source.

Use both keys when investigating:

1. Search Loki for the response `X-Request-ID`, for example
   `{compose_service="portal-service"} | json | requestId="task11-..."`.
2. Open the `traceId` derived link from the log line, or call `GET /api/traces/{traceId}` in Tempo.
3. Follow Redis Stream `CONSUMER`, Outbox `PRODUCER`, Rabbit consumer, callback HTTP, and Agent spans.
4. Use low-sensitive `event`, `outcome`, and business summary fields to confirm MySQL/Outbox/final
   state. Never paste credentials into Explore queries.

## Repeatable verification

The verification script uses health/readiness polling with bounded exponential backoff. It loads
deterministic dev data, activates and loads activity `900001`, sends a real reservation, waits for
the asynchronous order, schedules a duplicate successful Mock Payment callback, verifies final
state, queries Prometheus/Loki/Tempo/Grafana, restarts all observability containers, and checks again.

```powershell
.\script\verify-observability.ps1
```

If the stack is already built and running:

```powershell
.\script\verify-observability.ps1 -SkipStartup
```

The script injects a known non-production sentinel as the Mock Payment secret and fails if that
value appears in Docker logs, Loki results, the queried Tempo trace, or Prometheus metric names.
It prints request, trace, reservation, order, and payment identifiers only after all checks pass.
For repeatability it resets only Redis keys for demonstration activity `900001` before reloading
that activity; it does not flush either Redis database.

## Local alert exercises

These are demonstration thresholds, not production SLOs.

- target down: stop one app container for more than 30 seconds;
- HTTP 5xx/latency: call a test failure path or use a fault proxy;
- HikariCP: constrain the local pool and run concurrent requests;
- Redis Lua: make `redis-seckill` unavailable during a reservation;
- Stream Pending/lag: pause `task-service` while reservations continue;
- Outbox age/backlog: stop RabbitMQ while Outbox remains enabled;
- Rabbit nack/return/DLQ: use the existing reliable-message failpoints/tests;
- compensation/reconciliation: run the existing failure/reconciliation container tests;
- payment callback: stop portal while callback delivery retries;
- Agent: use the fake provider failure tests to open the circuit.

Restore the stopped component, then wait for the corresponding `resolved` state. Do not delete named
volumes to clear alerts. Loki, Tempo, and Prometheus retain seven days by default.

## Rebuild and recovery

`docker compose --profile observability restart prometheus loki tempo alloy grafana` preserves data
and reuses provisioning. Recreate is also deterministic because configs live under
`docker/observability`; named volumes contain only runtime data. If a config check fails, first run:

```powershell
docker compose --env-file .env.example --profile observability config --quiet
```

Do not put Authorization, cookies, keys, full bodies, or full model prompts into diagnostic logs or
Grafana annotations. Rotate any real secret immediately if it was ever emitted before TASK-11.
