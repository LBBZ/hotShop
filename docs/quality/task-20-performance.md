# TASK-20 本机高并发压测与性能基线

## 结论状态

本页只记录实际运行数据。代码交付阶段的运行结果见“实际验证结果”；没有对应证据的
5000 RPS、持续容量或延迟结论一律标为“未验证”。目标是待验证门槛，不是已实现承诺：
新请求 5000 RPS、接入 P99 不超过 200 ms、异步订单 P99 不超过 3 s、超卖/重复有效订单/
对账差异均为 0。

## 测试口径

测试环境是执行报告 `hardware.json` 中的本机 CPU、逻辑核数、内存和操作系统，版本来自
`versions.json`，容器镜像来自 `images.jsonl`。k6 固定为 0.54.0，并锁定多架构镜像摘要
`sha256:1f40432b1cbe7234e977f96c362c9bc550a2d2b583d014dd8669fe40d3e9e755`。应用、MySQL、
Redis、RabbitMQ、Prometheus、Grafana 与 k6 都在同一个、按 Run ID 隔离的 Compose 网络。

arrival rate 是调度目标，RPS 是实际完成的请求尝试率，VU 是用于达到该速率的并发执行
上下文；三者不能互换。完整平台必须达到目标速率、`dropped_iterations=0`、系统错误在
阈值内、P99 达标、事实正确且异步链路排空。短时最大值不是持续容量。10000 VUs 仅可由
显式参数尝试，不是默认门槛。

新请求使用唯一用户和唯一幂等键并标记 `intent=new`。幂等重放使用完全相同的指纹，标记
`intent=replay`，独立统计，不能计入接入吞吐。登录处于 `scenario=prepare`，不会混入
`hotshop_new_intent_duration_ms`。

## 场景

- `smoke`：登录、活动查询、预约、状态查询、订单落库完整链路。
- `read-baseline`：只测查询吞吐，不充当写吞吐。
- `seckill-new-intent`：100/250/500/1000 RPS 可配置阶梯，默认每个平台 10 秒。
- `idempotency-replay`：验证相同结果、一次库存和一次订单事实。
- `oversell-boundary`：请求数超过库存，核验售罄契约和三方库存事实。
- `mixed-e2e`：50% 公开活动查询、30% 新请求、20% 已认证用户查询。
- `agent-isolation`：FakeModel Agent 与交易并行；仅开放 `catalog:read` 低风险能力。

开放模型测试使用 arrival rate，允许系统显露饱和点；封闭模型测试使用固定 VU/迭代，
用于验证一条限定工作流。Agent 使用确定性 FakeModel，结果只代表本项目编排和接口开销，
不代表真实大模型。k6 将 SSE 视为一个完整 HTTP 响应，因此报告的是完整流完成时间，不是
逐事件实时延迟。

## 指标与事实

k6 记录实际 RPS、dropped iterations、HTTP 状态、P50/P90/P95/P99/max 和低基数业务结果。
Prometheus 同时采集 JVM 堆/GC/线程、HikariCP、应用、Agent、RabbitMQ 和积压指标；脚本
采集 Docker CPU/内存、MySQL 连接/慢语句、Redis 内存/命令/延迟及 RabbitMQ 队列快照。

异步订单的规范计算是
`TIMESTAMPDIFF(MICROSECOND, sale_reservation.reserved_at, sales_order.created_at)/1000`。
报告用最近秩法计算 P50/P95/P99/max/sampleCount。Grafana 的低基数
`hotshop.seckill.order.end_to_end_latency` 用于运行中观察，最终结论以持久化时间查询为准。

每个写场景核验初始/Redis/MySQL 库存、预约、有效订单、一预约一订单、重复订单、超卖、
Stream 长度与 pending、处理失败/卡住事件、Outbox、RabbitMQ、对账差异和活动状态。

## 正式运行与复现

```powershell
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile smoke
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile baseline
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile target-5k
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile agent-isolation
```

默认结束时仅对 `hotshop-task20-<run-id>` 执行 `down --volumes --remove-orphans`，并按 Compose
project label 验证容器、网络、卷均为 0。故障调查可加 `-KeepStack`，之后使用报告中的精确
project 名手工执行相同 down 命令；禁止按镜像名、模糊名称或全局 prune 清理。

GitHub hosted runner 只执行 Compose、k6 解析和 PowerShell 静态检查。共享、虚拟化且会漂移
的 runner 不能生成本机容量结论，正式 baseline、5000 RPS、断点和 10000 VU 不进入普通 CI。

## 实际验证结果

运行日期为 2026-09-14。宿主机为 Windows 10.0.26200、Intel Core i9-13900HX（24 核/
32 逻辑处理器）、16.91 GB 物理内存；Docker VM 可用内存 8.18 GB。Docker 29.6.2、
Compose 5.3.1、k6 0.54.0。以下都是短平台实测，不宣称持续容量。

| 证据 Run ID | 场景 | 目标/实际 RPS | VUs | 时长 | 新请求 P95/P99 | 异步订单 P95/P99 | 结果 |
|---|---|---:|---:|---:|---:|---:|---|
| `run-smoke-final-002` | smoke | 5 个闭合迭代 | 5 | 3 s | 165.49/165.50 ms | 2775.80/2775.80 ms | 5/5 订单，正确性/完整性通过 |
| `run-baseline-final-005` | read baseline | 500/500.0 | 100 | 2 s | 不适用 | 不适用 | 0 dropped，检查通过 |
| `run-baseline-final-005` | new intent | 100/100.5 | 100 | 2 s | 11.11/64.07 ms | 3306.51/3312.77 ms | 201/201；异步 P99 超 3 s |
| `run-baseline-final-004` | new intent | 250/250.0 | 250 | 2 s | 5.39/10.73 ms | 6553.88/6626.81 ms | 299 接受、201 业务拒绝 |
| `run-baseline-final-004` | new intent | 500/500.0 | 500 | 2 s | 104.68/193.66 ms | 11624.10/12044.06 ms | 500 接受、500 业务拒绝 |
| `run-baseline-final-004` | new intent | 1000/531.0 | 1000 | 2 s | 1391.01/3681.12 ms | 1823.32/2106.80 ms | 1061 dropped；1062 尝试中 1000 业务拒绝 |
| `run-target5k-final-002` | 5000 RPS 探索 | 5000/500.0 | 500 | 1 s | 369.48/1789.03 ms | 11103.48/11318.69 ms | 995 dropped；5000 RPS 未达到 |
| `run-agent-final-004` | Agent + transaction | 5/5.333 新请求 | 20 | 3 s | 25.67/58.20 ms | 3945.01/3945.01 ms | Agent 完整流 3 次，P95/P99 352.40/360.88 ms |

`run-baseline-final-005` 还实际完成了：幂等重放 10 个新意图/20 个接受响应且只有 10 个订单；
超卖边界 100 次尝试、25 接受、75 售罄；混合场景总计 100.5 RPS，其中新意图 30 RPS，
与 50/30/20 配比一致。三个场景的超卖、重复有效订单、对账差异、Stream pending、Outbox
pending、Inbox pending 和系统错误均为 0。所有最终 Run 的隔离容器、网络、卷清理计数均为 0，
产物脱敏与证据完整性均通过。

5000 RPS 未验证为可达，更未验证为持续容量。显式 500 VU 生成器预算首先产生 995 个
`dropped_iterations`，同时接入与异步 P99 均越线。100 RPS 平台接入达标但异步 P99 为
3312.77 ms，已超过 3 s；因此没有任何完整平台同时满足全部 SLO，`highestCredibleSustainedRps`
为未验证。优先级是先提高/横向扩展 Stream 订单消费者并观察端到端直方图，再隔离压测生成器
资源、逐级提高 VU 预算复测；RabbitMQ `messages_ready` 在各运行后仍有 32–2124 条，来自当前
测试栈没有下游业务事件消费者，也应在容量验收环境补齐消费者或单独定义排空口径。
