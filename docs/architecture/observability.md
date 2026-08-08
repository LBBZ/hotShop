# HotShop observability architecture

TASK-11 establishes one reproducible local telemetry plane. It is an engineering diagnostic system,
not a production SLO claim.

## Topology

- Prometheus scrapes `/actuator/prometheus` from `portal`, `admin`, and `task`, `/metrics` from
  `agent`, and Alloy's own metrics. Retention is seven days with a 4 GB local cap.
- Java emits OTLP/HTTP traces to Alloy. Python emits Zipkin v2 spans to Alloy without adding a
  second Python telemetry dependency tree. Alloy batches both formats and sends OTLP to Tempo.
- Alloy discovers Docker containers through the read-only Docker socket, parses JSON logs, and
  sends them to Loki. Only `service`, `environment`, and `level` become Loki labels.
- Grafana is fully provisioned from the repository. Prometheus exemplars open Tempo traces; Tempo
  opens matching Loki logs; Loki's `traceId` derived field opens Tempo. No UI setup is required.

All five observability services use fixed image tags, named volumes, health checks, resource limits,
and the independent Compose profile `observability`. Configuration is bind-mounted read-only, so a
container rebuild replays the same datasources, dashboards, and alerts.

The local Java container entrypoint caps tiered compilation at C1. This avoids a reproducible
Temurin 21 Alpine C2 SIGSEGV under Docker Desktop; it is a local-stack stability choice, not a
production JVM tuning recommendation.

## Context contract

`X-Request-ID` is a user-visible correlation key. `traceparent`/`tracestate` are W3C tracing
context. They are deliberately separate.

1. The browser adapter creates a valid request ID and W3C `traceparent` for every API request and
   preserves valid caller context.
2. Spring/Micrometer creates server spans and exports `service.name` plus
   `deployment.environment`. The request filter returns `X-Request-ID` and `X-Trace-ID` and adds
   `requestId`, `traceId`, and `spanId` to MDC.
3. The Redis Lua span is a child of the HTTP span. Because the accepted Stream schema was already
   immutable, the producer stores the W3C carrier in a seven-day, SHA-256-keyed Redis correlation
   entry. The Stream event already carries the request ID; no database migration or mutable Stream
   rewrite is used.
4. First Stream delivery creates a `CONSUMER` span with the producer as remote parent. A claimed
   Pending/redelivery starts a new trace and adds a Micrometer `Link` to the producer context. This
   avoids pretending that a thread-local survived Redis or a process restart.
5. The MySQL transaction persists `requestId` and `traceparent` in the existing Outbox JSON payload.
   The scheduled publisher restores that remote parent, creates a `PRODUCER` span, and writes the
   current `traceparent`, optional `tracestate`, and `x-request-id` to RabbitMQ headers.
6. Rabbit listeners use Spring AMQP observations. Explicit retry republishes copy only the three
   allowlisted context headers; retry counts are separate low-cardinality headers. DLQ messages keep
   broker context without copying Authorization, Cookie, or bodies to logs.
7. Mock Payment delivery copies W3C/request headers to its delayed HTTP callback. Portal server
   tracing therefore covers signature validation, idempotency, terminal-state competition, MySQL,
   audit, and payment Outbox creation.
8. Agent HTTP middleware accepts the same W3C carrier. Agent model spans, structured logs, provider
   usage, rate-limit, and circuit-breaker metrics share that context. Prompt contents and model
   deltas are never logged or attached to spans.

Business IDs may appear in bounded log summaries and span attributes when needed for diagnosis,
but never as Prometheus labels. Request, trace, user, order, and reservation identifiers are rejected
by the common MeterFilter if a new meter attempts to use them as tags.

## Log and secret boundary

Java and Python console logs are one JSON object per line with:

`timestamp`, `level`, `service`, `environment`, `event`, `name`, `requestId`, `traceId`, `spanId`,
`outcome`, `errorType`, and a bounded message.

The encoders omit stack traces and sanitize Bearer credentials, Authorization, Cookie, passwords,
API keys, all token classes, payment secrets/signatures, model prompts, and request/response bodies.
The primary rule remains “do not log sensitive input”; sanitization is a defensive final boundary.

## Metrics and labels

Standard Spring metrics provide HTTP RED, JVM memory/GC/threads, and HikariCP. TASK-11 adds real
code-path meters:

| Area | Metrics |
|---|---|
| Redis Lua | `hotshop_redis_lua_calls_total`, `hotshop_redis_lua_duration_seconds` |
| Stream | consumed/processed/retry/claim/quarantine/compensation counters, Pending, oldest idle, lag, conversion latency |
| Outbox | backlog, oldest age, publish status/duration, broker ack/nack/return |
| RabbitMQ | consume/retry/reject/DLQ outcome counters |
| Inventory | compensation and reconciliation outcomes |
| Payment | callback business result/duration and delivery outcome |
| Agent | latency, provider outcome, tokens, cost, tool outcomes, error category, concurrency, circuit state |
| k6 | standard k6 Prometheus remote-write metrics consumed by the provisioned load dashboard |

All tags are bounded enums such as `operation`, `outcome`, `provider`, `tool`, and `error type`.

## Dashboards and alerts

Provisioned dashboards are Transaction Overview, Messaging Reliability, Agent, and k6 Load Test.
Prometheus loads all local alert rules; Grafana also provisions the target-down managed rule,
contact point, and notification policy. Every threshold is labelled `scope=local-demo` and is
intentionally sensitive. It must not be described as a production SLO.
