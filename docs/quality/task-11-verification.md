# TASK-11 verification record

Date: 2026-08-08. Scope: local development observability; thresholds are not production SLOs.

## Implemented checks

- Compose profile, fixed images, health checks, retention, resource limits, and provisioning.
- Prometheus metrics, Loki request lookup, Tempo cross-service trace lookup, and Grafana dashboards.
- Java W3C/async propagation, bounded metrics, JSON log redaction, Outbox, and Mock Payment paths.
- Python correlation, trace export, JSON redaction, metrics, pytest, Ruff, and mypy.
- Browser request ID and W3C propagation through the generated-client fetch adapter.
- A real reservation → Stream → MySQL Order → Outbox → RabbitMQ → Mock Payment callback → final
  state flow, plus Agent HTTP, observability-container restart, and sentinel-secret scanning.

## Actual results

- `docker compose --env-file .env.example --profile app --profile agent --profile observability
  config --quiet`: passed; no `latest` image references were found.
- Common Java safety/propagation/label tests: 4 passed.
- Task metrics/Outbox/timeout-delivery tests: 18 passed.
- Portal Mock Payment integration tests with Ryuk disabled: 8 passed.
- Python: 116 pytest tests passed; Ruff passed; strict mypy passed for 20 source files.
- Web: 7 Vitest files / 20 tests passed, including 3 request-context tests; TypeScript typecheck
  passed.
- Full final-state `verify-observability.ps1`: passed with request ID
  `task11-8b9355a6c93a47c396dfb25cee5c5207`, trace ID
  `ea24d105570cc948f54e8cd2e64ccb1c`, reservation
  `rsv_c81fc2590a2b45a287b9a0d9c61445c3`, order
  `ord_16f726b6a314de9009ce40bbdb534c46`, and payment
  `MOCK_d29c9ebd2a5d4777971f748cc61262a2`.
- The script found all 7 required metric families, one Loki stream for that request, all 4
  dashboards, and a Tempo trace containing `admin`, `agent`, `portal`, and `task` resource batches.
- Prometheus, Loki, Tempo, Alloy, and Grafana were restarted; readiness, provisioning, dashboards,
  and the retained trace were verified afterward.
- Sentinel scan across Docker logs, Loki, Tempo, and the complete Prometheus series response found
  no `TASK11_SENTINEL_SECRET_7f63a2f9` occurrence.
- A final direct W3C probe after the file-boundary audit produced a Loki line with request ID
  `task11-range-safe`, trace ID `8bf92f3577b34da6a3ce929d0e0e4736`, and span ID
  `ef3cbf7bb86fb501` from `common.observability.ObservabilityRequestFilter`.

The local PowerShell profile prints a non-fatal Conda GBK activation diagnostic after some Maven
commands; Maven itself returned exit code 0 and its Surefire reports contain the counts above.
