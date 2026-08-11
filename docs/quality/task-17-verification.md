# TASK-17 verification

## Machine gates

All Python commands run inside the pinned Agent container; no host JDK or host Python environment
is required. The authoritative reusable journey is `script/verify-task17-compose.ps1`.

Required thresholds are encoded in `agent/src/hotshop_agent/eval_runner.py` and results use
`schemaVersion=1.0`, `datasetVersion=task17-v2`. A failed safety case always makes its 100%
category miss the threshold and returns a non-zero process exit.

The runner validates the complete JSONL dataset before it creates an evaluation runtime or opens a
Qdrant client. Empty files and non-object/invalid JSON lines are rejected. Every case uses an
extra-forbidden strict schema: `id` and `query` are non-blank, IDs are globally unique, `suites` is
a non-empty list containing only `quick`/`full`, and category/action/identity values are closed
enums. The action-to-category mapping and conditional fields are exact: route/outage cases require
`expectedTool`, retrieval/citation/tenant require `expectedDocumentId`, visibility requires
`forbiddenDocumentId`, and those fields are rejected on unrelated actions. Validation errors only
report a line number and safe field/reason summary; raw JSON and query text are never included.

Quick must contain retrieval, citation, refusal, security, authorization, and dynamic-routing
cases. Full must contain all of those plus lifecycle. A suite with zero cases or any missing
required category exits with code 2 before evaluation. `passedThresholds` additionally requires a
non-empty result set, an exact match to the suite's complete required-category set, and every
category threshold to pass; it never relies on vacuous `all([])` behavior.

The evaluation dataset covers FAQ/policy/rule hit@3, citation fields, empty/low-score refusal,
document/user prompt injection, USER/ADMIN visibility, tenant override, live price/inventory/
Order/Reservation routing, cross-user denial, absent high-risk tools, Qdrant outage independence,
update/delete/rebuild lifecycle, bilingual/colloquial dynamic facts, and ambiguous-fact refusal.
Provider contract tests cover Fake/DeepSeek/Qwen delta/usage/tool normalization, errors,
cancellation, Key privacy, reasoning suppression, registry immutability, and unchanged auth scope.

## Verification commands

```powershell
docker compose --env-file .env.example config --quiet
docker build --target test -t hotshop-agent:task17-test -f agent/Dockerfile agent
docker run --rm --entrypoint python hotshop-agent:task17-test -m ruff check .
docker run --rm --entrypoint python hotshop-agent:task17-test -m ruff format --check .
docker run --rm --entrypoint python hotshop-agent:task17-test -m mypy --no-incremental src tests
docker run --rm --entrypoint python hotshop-agent:task17-test -m pytest `
  tests/test_model_provider_contracts.py tests/test_qwen.py -p no:cacheprovider
docker run --rm --network <task17-network> -e AGENT_QDRANT_URL=http://qdrant:6333 `
  --entrypoint python hotshop-agent:task17-test -m pytest -p no:cacheprovider
docker build --target security-test -t hotshop-agent:task17-security -f agent/Dockerfile agent
docker run --rm --user 0:0 -e AGENT_CONTAINER_IMAGE=<runtime-image> `
  -v /var/run/docker.sock:/var/run/docker.sock --entrypoint python `
  hotshop-agent:task17-security -m pytest tests/test_container_security.py -p no:cacheprovider
```

Quick/full JSON results and Compose evidence are runtime artifacts under ignored `target/`; they are
not committed. The delivery report records the actual totals and raw command outcomes. This file
does not claim gates that were not executed.

## RECONCILE-02 executed results

- Dataset validation tests: `15 passed`, including CLI exit code 2 for empty, duplicate-ID, and
  missing-category datasets.
- Docker non-Qdrant suite: `243 passed, 6 skipped, 8 deselected`.
- Quick evaluation: `28/28`, all six required categories present, `failedCaseIds=[]`, and
  `passedThresholds=true`.
- Ruff: `All checks passed`; format: `52 files already formatted`.
- Strict mypy: `Success: no issues found in 52 source files`.
- Compose config validation returned exit code 0.

No DeepSeek, Qwen, or Bailian credential is used by these gates. The six skipped tests are the
separately executed Docker runtime-security cases; the eight deselected tests require real Qdrant
and are outside this reconcile's mandated non-Qdrant command.
