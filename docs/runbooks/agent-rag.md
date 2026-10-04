# Agent RAG runbook

## 区分空结果与不可用

本地演示以 [README](../../README.md) 的隔离启动为准。默认 FakeModel/deterministic、阈值 0.15。
当前 1.1.0 语料共 10 份，中英文使用独立文档 ID 和 locale，引用指向维护中的[使用说明](../product/help.md)。
“售后申请应从哪里发起？”和“售后退换申请应该怎么做？”均应引用中文售后资料；
“Where can I request a refund?” 应引用英文资料。答案必须说明演示没有售后申请入口。
“常见问题：火星门店在哪里？”使用真实资料库验证空结果。deterministic 是词项/字符特征检索，
不保证任意语义改写命中；这里没有降低全局阈值，也没有验证真实模型的语言质量。

运行中日志`event=agent.rag.retrieval`记录hit/empty/unavailable、requestId/traceId/runId和安全errorType。
empty表示请求成功但无合格候选；unavailable按embedding_failure、qdrant连接/超时/传输/HTTP/响应等类别诊断。
不输出异常消息、问题正文或密钥。独立进程检索成功或healthz正常不能证明原请求成功。
同一运行Agent的真实Qdrant断连/恢复入口为`node script/verify-reconcile-rag.mjs <本worktree项目名> <本地WebURL>`。
脚本要求该 worktree 的 demo 配置、唯一所属容器、匹配的 Web 端口和 FakeModel。
它核对中英文首位引用、全部引用字段、真实回答中的功能边界、无关问题拒答及动态工具；
只 Stop/Start 所选项目的 Qdrant，不删除卷，结束时检查 Agent 进程未重启。
历史瞬时RetrievalError根因仍未决；[证据与限制](../quality/task-21-reconcile-01.md)保留原失败。

## Start and index

The default Qdrant image is `qdrant/qdrant:v1.19.1` (overridable through `QDRANT_IMAGE`). It has its own persistent volume, health check, CPU/memory
limits, configurable host port, and the existing `hotShop-network`. Agent containers use
`http://qdrant:6333`; host tools may use `QDRANT_PORT`. Run these commands from the repository root
after preparing the local auth keys described in [Agent setup](agent-service.md). The isolated
[demo entry point](container-environment.md) handles keys, startup and the initial index automatically.

```powershell
pwsh -NoProfile -File ./script/demo.ps1 -Action Start
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent --profile agent exec -T agent-service python -m hotshop_agent.index_cli validate
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent --profile agent exec -T agent-service python -m hotshop_agent.index_cli rebuild
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent --profile agent exec -T agent-service python -m hotshop_agent.index_cli status
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

Full integration checks run only on GitHub-hosted CI, using disposable resources and automatic cleanup. See [CI verification](../quality/ci.md).

For a local offline evaluation, activate the Python 3.12 environment described in [CONTRIBUTING](../../CONTRIBUTING.md) and run from `agent/`:

```powershell
python -m hotshop_agent.eval_runner --suite quick --output ../target/agent-quick.json
```

Quick and
full evaluations use FakeModel/DeterministicEmbedding. Dataset `task17-v3` contains 48 quick cases
and 49 full cases; the full suite adds the real-Qdrant rebuild lifecycle. Reports include
schema/dataset/provider, the dataset SHA-256, source `knowledgeVersion`, category rates, thresholds,
and failed IDs. The source hash identifies the chunked corpus; the active collection version also
includes embedding configuration and is a different value. Retrieval cases must pass the actual
static router. Citation cases require the expected first document and exact source metadata;
unrelated-question cases use the real corpus with the default threshold. Security, authorization, dynamic routing, citations,
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
