# TASK-20 性能证据：RECONCILE-02

## 当前结论与代码归属

代码证据提交 A：`550231abfc9fde3228b307f88e1de99b5c35f3cb`。
开发分支：`task-20-reconcile-02`，从 `task-20-reconcile-01` 创建。
文档提交 B 是紧随 A 的本文件更新提交，可用 `git log -1 --format=%H -- docs/quality/task-20-performance.md`
解析；交付回复记录 B 的完整 SHA。A..B 只修改本文档。

默认 target-5k 已形成有效探索结果：k6 正常退出、有实际预约、全部正确性与完整性门禁通过、
资源清理为零。**5000 RPS 未达到**，不能把本次结果描述为持续 5000 RPS 容量。

RECONCILE-01 的四个 RunId 仅是其原提交上的历史结果；其 1 秒、500 VU 探索不能作为默认
10 秒、1000 VU 命令的证据。更早的 `run-baseline-final-*`、`run-agent-final-*`、
`run-target5k-final-*` 已作废。当前正式证据只引用下述全新 RunId。

## 身份准备与内存约束

旧实现 setup 返回 50001 个访问令牌，k6 将 setup 数据复制给 VU，形成随用户数乘以 VU 数
增长的令牌存储。新 target-5k setup 只返回 `{ mode: 'iteration-login', size: 50001 }`，
序列化后小于 128 bytes；每个业务迭代按全局 iteration 索引选取一个唯一用户并调用真实登录
接口，访问令牌只存在于该迭代局部变量中。身份池越界、无效索引、登录失败或无令牌均失败关闭。

没有用户池复用，没有 modulo 回绕，没有提高每用户购买上限，没有修改应用鉴权或数据模型。
每个用户仍只有一个 new-intent，不会因身份复用引入热点用户或额外的同用户锁竞争。
new-intent 键仍包含 RunId、activityId、scenario、iteration；replay 专用场景仍使用同一键和
同一请求指纹验证重放。

压测语义发生明确变化：target-5k 现在测量“唯一用户登录后预约”的工作流，登录耗时占用 VU，
因此会限制可发出的预约数；接入 P95/P99 仍只测预约 HTTP 请求，不含登录。登录请求标记为
`prepare`，不混入预约计数。这不是预先持有 JWT 的纯预约接口容量基准。

内存中的令牌数量随同时执行的迭代数增长，不再随全部用户数乘以 VU 数增长。2 GiB k6 限制、
5000 requested RPS、1000 VU、10 秒默认时长、50001 用户/库存均未修改。

## 精确命令与正式证据

在提交 A 的干净工作树上执行以下命令，退出码 0：

```powershell
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile target-5k -RunId run-reconcile02-default-0915-01
```

这是默认 target-5k 命令，仅显式指定 RunId；没有传 Rate、VUs、Duration、Warmup、SkipBuild
或 KeepStack。参数文件中的请求字段 rate/vus 为 0、duration 为空，表示采用默认值；实际执行
参数见 `parameters.json.plan` 和 `summary.json.scenarios`：

| 参数 | 实际值 |
|---|---:|
| requested RPS | 5000 |
| duration / warmup | 10s / 2s |
| VUs（业务场景） | 1000 |
| users / inventory | 50001 / 50001 |
| k6 内存上限 | 2147483648 bytes |

再次复现时可原样执行自动生成全新 RunId 的默认命令：

```powershell
pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile target-5k
```

固定证据 RunId 已非空，再次使用会被拒绝。正式 summary：
`target/task20-performance/run-reconcile02-default-0915-01/summary.json`。
其中 `gitHead=550231abfc9fde3228b307f88e1de99b5c35f3cb`，`worktreeCleanAtStart=true`。

## 实际结果

| 指标 | 实测 |
|---|---:|
| requested / actual RPS | 5000 / 220.6 |
| attempted / accepted | 2206 / 2206 |
| dropped iterations | 47791 |
| 预约 P95 / P99 | 4547.55 / 5088.75 ms |
| 异步订单 P95 / P99 | 49785.16 / 52094.12 ms |
| k6 采样峰值 CPU | 49.60% |
| k6 采样峰值内存 | 753611571 bytes（约 718.7 MiB） |
| k6ExitCode | 0 |
| 5xx / 幂等冲突 / 业务拒绝 | 0 / 0 / 0 |
| evidenceComplete / businessCorrectnessPassed / runIntegrityPassed | true / true / true |
| ownedResourcesZero / target5000RpsActuallyReached | true / false |

actual RPS 的现有明确口径为完成预约数 / 10 秒到达窗口，包含窗口后 graceful-stop 内完成的
预约，不代表整个运行期间持续达到该速率。原始 k6 执行（包含 2 秒 warmup 和尾部完成时间）
为 15.8233 秒；无 interrupted iteration。不要将 220.6 解读为稳定容量。
采样峰值不是进程全生命周期的精确高水位：本次捕获到 3 个 k6 容器样本。
portal 采样峰值 CPU 2909.01%；登录与预约共用服务资源，认证 CPU 负载及 1000 VU 预算限制
了工作流吞吐，异步订单延迟也明显超过 3 秒目标。性能门禁保持 false。

## 正确性、消息与清理

预约数与有效订单均为 2206，初始库存 50001，MySQL/Redis 剩余库存均为 47795。
超卖、重复有效订单、对账差异、failed/stuck、outboxPending、inboxPending、streamPending、
Rabbit unacknowledged 与未解释 ready 均为 0；`asyncDrained=true`。

Rabbit raw ready=4412，全部属于既有明确排除边界：

- `hotshop.order.created.v1`：`ORDER_CREATED`，2206 条；仓库无该集成队列消费者，逐订单
  要求 PUBLISHED outbox 证明，`orderCreatedPublishProofMissing=0`。
- `hotshop.order.timeout.delay.v1`：`LEGACY_ORDER_TIMEOUT_REQUESTED`，2206 条；15 分钟
  TTL 延迟队列，逐订单要求 PUBLISHED outbox 证明，`timeoutPublishProofMissing=0`。

上述门禁和排除规则未修改，所有 unacknowledged 与其他队列仍参与门禁。
Prometheus 必需查询 request-count / new-intent P99 / scrape-up 样本数为 2 / 1 / 7。
证据脱敏检查通过；令牌不写入正式证据或临时令牌文件。

正式 project `hotshop-task20-run-reconcile02-default-0915-01` 的 cleanup 为容器/网络/卷
0/0/0。受控失败 RunId `run-cleanup-5ebf8999`：内部 harness 预期退出 1，复用非空 RunId
预期退出 1，资源 0/0/0，sentinel 项目未被清理。

## 提交 A 上的验证

以下命令均在提交 A 的干净工作树上执行，退出码均为 0：

```powershell
pwsh -NoProfile -File script/ci/tests/test_task20_planning.ps1
pwsh -NoProfile -File script/ci/tests/test_task20_policy.ps1
pwsh -NoProfile -File script/verify-task20-static.ps1
pwsh -NoProfile -File script/ci/tests/test_task20_cleanup.ps1
```

规划回归验证 5000 RPS、10s、1000 VU、50001 用户/库存；k6 回归 9 项断言全部通过，覆盖
有界身份数据、身份耗尽失败关闭、唯一身份、跨阶段 iteration=0 的不同键和明确 replay 语义。
static 同时解析 Compose 并验证宿主机端口边界。证据脱敏、参数/提交绑定核验、
`git diff --check` 与最终资源检查均通过。最终 `git status --short` 为空。未合并、未推送。
