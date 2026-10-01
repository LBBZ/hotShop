# Agent RAG runbook

## 区分空结果与不可用

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

The default Qdrant image is `qdrant/qdrant:v1.19.1` (overridable through `QDRANT_IMAGE`). It has its own persistent volume, health check, CPU/memory
limits, configurable host port, and the existing `hotShop-network`. Agent containers use
`http://qdrant:6333`; host tools may use `QDRANT_PORT`. Run these commands from the repository root
after preparing the local auth keys described in [Agent setup](agent-service.md). The isolated
[demo entry point](container-environment.md) handles keys, startup and the initial index automatically.

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
logs. No real model or embedding key is used by the Compose verification scripts.

`text-embedding-v4` accepts at most 10 inputs per request. The provider advertises that capability
and the indexer splits 25/100+ chunks accordingly; operators must not raise it by changing the
chat-model Provider. DeepSeek/Qwen selection and Bailian/deterministic embedding selection are
separate settings.

## Evaluation and tests

The supported full integration entry point creates a unique Compose project, indexes knowledge,
checks Agent/Qdrant outage and restart behavior, captures evidence, and cleans its owned resources:

```powershell
pwsh -NoProfile -File .\script\verify-task17-compose.ps1
```

Evidence is written to ignored `target/task17-compose-evidence`. The script uses temporary local
keys and random host ports. Cleanup is scoped to the resources it created; do not substitute a
shared project name or manually remove a pre-existing volume.

For a standalone offline quick evaluation, build the test image and execute:

```powershell
docker build --target test -t hotshop-agent:test -f agent/Dockerfile agent
docker run --rm --entrypoint python hotshop-agent:test -m hotshop_agent.eval_runner --suite quick --output /tmp/quick.json
```

The quick suite uses in-memory vectors; its `/tmp/quick.json` exists only inside that disposable
container. Use the Compose verification entry point for retained integration evidence. Quick and
full evaluations use FakeModel/DeterministicEmbedding and report schema/dataset/provider,
category rates, thresholds, and failed IDs. Security, authorization, dynamic routing, citations,
and refusals require 100%; quick retrieval hit@3 requires 100%; full requires at least 90%.
These are gate thresholds, not a claim that a new environment has already passed.

## Failure handling

Qdrant requests use a 2-second default timeout and one retry with bounded backoff. Static questions
then emit `outcome=unavailable` and a clear “temporarily unavailable/no reliable information”
answer. There is no unbounded retry. Agent readiness continues to reflect the session store, so a
running Agent can execute dynamic Java tools while Qdrant is down. Ordinary Java APIs do not have
a Qdrant dependency.

If rebuild fails, inspect safe outcome/count logs, fix the source or provider, run `validate`, then
retry `rebuild`. The previous alias remains usable. Never repair aliases using model/user input or
place product/order/inventory snapshots into a knowledge document.
