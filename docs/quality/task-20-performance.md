# TASK-20 本机高并发压测与性能基线

## 结论

本页只把提交 `289165ad9e795030a5b814673ee205637cb61ba2`（代码证据提交 A）上、
`worktreeCleanAtStart=true` 的全新运行作为当前证据。文档提交 B 是交付时的 `HEAD`；其精确
SHA 由 `git rev-parse HEAD` 获得并记录在交付回复中。提交 A 到 B 之间只允许修改本文件。

本机没有达到 5000 RPS。10 秒 new-intent 平台在 100 和 250 RPS 时完成目标到达率且无
dropped iteration，但异步订单 P99 分别为 15.30 s 和 56.87 s，未满足 3 s 目标。500 RPS
平台实际为 184.4 RPS，并丢弃 3156 次迭代。1 秒 5000 RPS 探索实际为 500.0 RPS，丢弃
1002 次迭代，接入 P99 2077.86 ms，异步 P99 11.85 s。因此没有可宣称满足完整 SLO 的
持续容量，不能把 requested RPS 当作 achieved RPS。

此前文档引用的 `run-baseline-final-*`、`run-agent-final-*`、`run-target5k-final-*` 及其他
旧 RunId 全部作废，不得作为当前实现、简历或答辩证据。

## 口径与门禁

- arrival rate 是调度目标；actual RPS 是场景内实际完成的业务尝试数除以阶段时长；登录和
  warmup 不计入 new-intent 吞吐。
- new-intent 幂等键包含 RunId、activityId、scenario 和 iteration。replay 的两次请求才故意
  使用相同键与相同指纹。Problem Details 按精确 `code` 计数；new-intent/mixed 出现
  `IDEMPOTENCY_KEY_CONFLICT` 会使运行失败。
- 完整门禁要求 k6 成功、证据完整、无检查/系统错误、无超卖/重复有效订单/对账差异，并要求
  outbox、inbox、Redis Stream pending、失败或卡住事件、未解释 Rabbit ready 与全部 Rabbit
  unacknowledged 均为 0。警告不能绕过门禁。
- 默认用户池按 `ceil(rate * duration * new-intent 比例) + 1` 规划；mixed 默认 100 RPS、
  10 秒、30% new-intent，因此用户池为 301，不再使用会越界的固定 1000。
- 每个 RunId 使用独立 Compose project。已有且非空的证据目录会被拒绝。部分启动失败也在
  `finally` 中按精确 project name 执行 `down --volumes --remove-orphans`；`KeepStack` 只保留
  已完整启动且用户明确要求保留的环境。

RabbitMQ 有且仅有两个 ready 边界可以排除，unacknowledged 永不排除：

| 队列 | 事件类型 | 排除原因与不丢单证明 |
|---|---|---|
| `hotshop.order.created.v1` | `ORDER_CREATED` | 仓库内没有该集成队列的消费者；每个运行订单必须存在一条 `PUBLISHED` 的 ORDER_CREATED outbox 记录，否则 `orderCreatedPublishProofMissing` 使门禁失败。 |
| `hotshop.order.timeout.delay.v1` | `LEGACY_ORDER_TIMEOUT_REQUESTED` | 这是明确的 15 分钟 TTL 延迟队列；每个运行订单必须存在一条 `PUBLISHED` 的 timeout outbox 记录，否则 `timeoutPublishProofMissing` 使门禁失败。 |

这两个队列的 ready 数只代表已证明发布但不属于本次即时消费边界的消息。订单落库、预约/订单
一一对应、两类 PUBLISHED 证明、所有其他队列和所有 unacknowledged 仍被逐项检查，因而排除
不会掩盖订单链路消息丢失。

## 可原样执行的命令

```powershell
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile smoke
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile baseline -Rates 100,250,500 -Duration 10s
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile agent-isolation
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile target-5k
```

不传 RunId 时脚本自动生成全新值。正式证据使用的精确命令只额外指定下表中的 RunId；除首次
smoke 重建镜像外，其余使用 `-SkipBuild`，且都运行在同一个干净提交 A 上。

## 正式证据

运行环境：2026-09-14，Windows 10.0.26200，Intel Core i9-13900HX（24 核/32 逻辑处理器），
16.91 GB 物理内存；Docker 29.6.2、Compose 5.3.1、k6 0.54.0。所有运行的 `gitHead` 均为
提交 A，`worktreeCleanAtStart`、`runIntegrityPassed`、证据脱敏与最终资源清理均为 true。

| RunId | 场景 | requested / actual RPS | 尝试 / 接受 | P95 / P99 (ms) | dropped | 5xx | 精确拒绝原因 | 异步 P95 / P99 (ms) |
|---|---|---:|---:|---:|---:|---:|---|---:|
| `run-reconcile-a-smoke2-0914` | smoke（5 个固定迭代） | 5 / 0.5 | 5 / 5 | 79.50 / 79.51 | 0 | 0% | 无 | 2960.73 / 2960.73 |
| `run-reconcile-a-baseline2-0914` | read-baseline | 500 / 500.1 | 不适用 | 不适用 | 0 | 0% | 无 | 不适用 |
| `run-reconcile-a-baseline2-0914` | new-intent | 100 / 100.1 | 1001 / 1001 | 5.72 / 7.21 | 0 | 0% | 无 | 14720.16 / 15296.44 |
| `run-reconcile-a-baseline2-0914` | new-intent | 250 / 250.0 | 2500 / 2500 | 89.30 / 105.80 | 0 | 0% | 无 | 54924.64 / 56865.29 |
| `run-reconcile-a-baseline2-0914` | new-intent | 500 / 184.4 | 1844 / 1844 | 774.35 / 3089.24 | 3156 | 0% | 无 | 39058.37 / 40647.15 |
| `run-reconcile-a-baseline2-0914` | idempotency-replay | 1 / 10.0 | 10 键 / 20 响应 | 9.11 / 9.13 | 0 | 0% | 无 | 2069.41 / 2069.41 |
| `run-reconcile-a-baseline2-0914` | oversell-boundary | 50 / 50.5 | 101 / 25 | 5.84 / 8.30 | 0 | 0% | `FLASH_SALE_SOLD_OUT=76` | 1636.81 / 1647.02 |
| `run-reconcile-a-baseline2-0914` | mixed-e2e | 100 / 100.1 | 300 new / 300 | 5.33 / 6.84 | 0 | 0% | 无 | 2352.08 / 2730.79 |
| `run-reconcile-a-agent2-0914` | agent-isolation 交易流 | 20 / 20.067 | 301 / 301 | 8.08 / 13.36 | 0 | 0% | 无 | 337.10 / 381.29 |
| `run-reconcile-a-target5k2-0914` | 5000 RPS 探索 | 5000 / 500.0 | 500 / 500 | 1844.83 / 2077.86 | 1002 | 0% | 无 | 11489.51 / 11848.41 |

replay 以 10 个唯一意图各发送两次相同请求，得到 20 个成功响应，但只扣减 10 件库存并生成
10 个订单。所有正式 new-intent 与 mixed 阶段的 `IDEMPOTENCY_KEY_CONFLICT=0`。

Agent 与交易流量同时运行 15 秒：Agent 尝试 61、成功 61、失败 0、成功率 100%，P95/P99
为 419.00/442.60 ms；交易尝试 301，实际 20.067 RPS，P95/P99 为 8.08/13.36 ms。期间
采集 89 个容器资源样本，最大单容器 CPU 957.93%、最大内存 477,940,941 bytes；业务正确性、
消息积压门禁和该场景性能目标均通过。Agent 使用确定性 FakeModel，结论只代表本项目编排与
接口开销，不代表外部大模型服务。

5000 探索中 portal 峰值 CPU 496.90%，k6 峰值 CPU 401.51%、峰值内存约 2.14 GB，500 VU
预算下发生 1002 个 dropped iterations；瓶颈是生成器 VU/认证完成能力与 portal CPU 路径的
组合，而不是 5xx 或业务拒绝。该结果明确为未达到 5000 RPS。

## 消息积压与正确性

| RunId / 场景 | outbox | inbox | stream pending | Rabbit raw ready | 已声明排除 | 未解释 ready | unack | failed/stuck | 超卖 / 重复 / 对账差异 | 结果 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| smoke | 0 | 0 | 0 | 10 | 10 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| baseline read | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| baseline 100 | 0 | 0 | 0 | 2002 | 2002 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| baseline 250 | 0 | 0 | 0 | 7002 | 7002 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| baseline 500 | 0 | 0 | 0 | 10690 | 10690 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| replay | 0 | 0 | 0 | 10710 | 10710 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| oversell | 0 | 0 | 0 | 10760 | 10760 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| mixed | 0 | 0 | 0 | 11360 | 11360 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| agent-isolation | 0 | 0 | 0 | 602 | 602 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |
| 5000 探索 | 0 | 0 | 0 | 1000 | 1000 | 0 | 0 | 0 | 0 / 0 / 0 | 通过 |

baseline 的 Rabbit raw ready 是同一隔离 project 内逐阶段累计值；每阶段的排除数与 raw ready
完全相等，且两类 publish proof missing 均为 0。所有场景 `asyncDrained=true`、
`businessCorrectnessPassed=true`。性能门禁仍会因延迟、到达率或 dropped iterations 失败；
正确性通过不等于性能达标。

Prometheus 使用 k6 实际导出的 `k6_http_reqs_total` 与
`k6_hotshop_new_intent_duration_ms_p99` 查询。四个正式运行中，request-count、new-intent P99、
scrape-up 的样本数分别为 smoke 1/1/7、baseline 6/1/7、agent 2/1/7、5000 探索 2/1/7，
所有必需查询至少返回一个样本。

## 资源清理与证据安全

四个正式 RunId 的 cleanup 均为容器 0、网络 0、卷 0。受控失败测试在 mysql 已启动后故意
请求不存在的 Compose service，harness 非零退出；随后精确 project label 下容器/网络/卷为
0/0/0，另一个 sentinel Docker project 保持存在。对同一非空 RunId 的再次运行也被非零拒绝。

正式证据目录的脱敏扫描全部通过，不包含 JWT、密码、API Key、Authorization 头、完整用户
凭据或私钥。证据只记录聚合指标、匿名计数、版本、硬件和精确但不含秘密的命令描述。
