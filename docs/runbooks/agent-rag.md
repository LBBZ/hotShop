# Agent RAG runbook

## RECONCILE-01：区分空结果与不可用

本地演示以[README](../../README.md)的隔离启动为准。默认FakeModel/deterministic、阈值0.15，
“售后申请应从哪里发起？”有已知语料引用；原改写“售后退换申请应该怎么做？”保留为低分安全拒答案例。
不保证任意中文语义改写命中，不降低全局阈值来修饰结果。

运行中日志`event=agent.rag.retrieval`记录hit/empty/unavailable、requestId/traceId/runId和安全errorType。
empty表示请求成功但无合格候选；unavailable按embedding_failure、qdrant连接/超时/传输/HTTP/响应等类别诊断。
不输出异常消息、问题正文或密钥。独立进程检索成功或healthz正常不能证明原请求成功。
同一运行Agent的真实Qdrant断连/恢复入口为`node script/verify-reconcile-rag.mjs <本worktree项目名> <本地WebURL>`。
脚本要求该worktree的demo配置和唯一所属容器，只Stop/Start Qdrant，不删除卷。
历史瞬时RetrievalError根因仍未决；[证据与限制](../quality/task-21-reconcile-01.md)保留原失败。

## Start and index

Qdrant is pinned to `qdrant/qdrant:v1.15.4`, has its own persistent volume, health check, CPU/memory
limits, configurable host port, and the existing `hotShop-network`. Agent containers use
`http://qdrant:6333`; host tools may use `QDRANT_PORT`.

```powershell
docker compose --env-file .env.example --profile agent up -d --build redis-cache qdrant agent-service
docker compose --env-file .env.example --profile agent exec -T agent-service python -m hotshop_agent.index_cli validate
docker compose --env-file .env.example --profile agent exec -T agent-service python -m hotshop_agent.index_cli rebuild
docker compose --env-file .env.example --profile agent exec -T agent-service python -m hotshop_agent.index_cli status
```

`validate` needs no Qdrant call. `rebuild` is idempotent and switches the alias only after exact
count verification. `status` reports the active version and point count for operators; internal
collection names are not returned to Agent clients.

## Providers

Keep `AGENT_EMBEDDING_PROVIDER=deterministic` for tests, evals, and offline local verification. To
opt into Bailian, inject `AGENT_BAILIAN_EMBEDDING_API_KEY` outside Git and set provider/model/base
URL/timeout explicitly. Never place the key in `.env.example`, command history, evidence files, or
logs. No real model or embedding key is used by TASK-17 automation.

`text-embedding-v4` accepts at most 10 inputs per request. The provider advertises that capability
and the indexer splits 25/100+ chunks accordingly; operators must not raise it by changing the
chat-model Provider. DeepSeek/Qwen selection and Bailian/deterministic embedding selection are
separate settings.

## Evaluation and tests (Docker only)

```powershell
docker build --target test -t hotshop-agent:test -f agent/Dockerfile agent
docker run --rm --entrypoint python hotshop-agent:test -m hotshop_agent.eval_runner --suite quick --output /tmp/quick.json

docker compose -p hotshop-task17 --env-file .env.example --profile rag up -d --wait qdrant
docker run --rm --network hotshop-task17_hotShop-network `
  -e AGENT_QDRANT_URL=http://qdrant:6333 --entrypoint python hotshop-agent:test `
  -m hotshop_agent.eval_runner --suite full --output /tmp/full.json
docker run --rm --network hotshop-task17_hotShop-network `
  -e AGENT_QDRANT_URL=http://qdrant:6333 --entrypoint python hotshop-agent:test `
  -m pytest -p no:cacheprovider
docker compose -p hotshop-task17 --env-file .env.example --profile rag down -v
```

The quick suite is offline and uses in-memory vectors. Full uses real Qdrant and checks lifecycle
semantics. Both use FakeModel/DeterministicEmbedding and produce JSON with schema/dataset/provider,
category rates, thresholds, and failed IDs. Security, authorization, dynamic routing, citations,
and refusals require 100%; quick retrieval hit@3 requires 100%; full requires at least 90%.

For the complete Compose journey, including Agent, indexing, Qdrant restart/persistence, outage
behavior, and finally cleanup:

```powershell
.\script\verify-task17-compose.ps1
```

Evidence is written to ignored `target/task17-compose-evidence`. The script uses a unique Compose
project, temporary local keys, random host ports, and removes containers/network/volume/keys in
`finally`.

## Failure handling

Qdrant requests use a 2-second default timeout and one retry with bounded backoff. Static questions
then emit `outcome=unavailable` and a clear “temporarily unavailable/no reliable information”
answer. There is no unbounded retry. Agent readiness continues to reflect the session store, so a
running Agent can execute dynamic Java tools while Qdrant is down. Ordinary Java APIs do not have
a Qdrant dependency.

If rebuild fails, inspect safe outcome/count logs, fix the source or provider, run `validate`, then
retry `rebuild`. The previous alias remains usable. Never repair aliases using model/user input or
place product/order/inventory snapshots into a knowledge document.
