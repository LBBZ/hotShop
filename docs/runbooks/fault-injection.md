# TASK-19 fault injection runbook

本手册只允许操作脚本创建的 `hotshop-task19-<随机值>` Compose project。开始前脚本查询同 project label
的容器、卷、网络以及预定 image tag；任一已存在即拒绝启动。禁止把 project name、资源 ID 或清理目标
改成通配符、workspace 根目录或用户已有 project。

## 入口与证据

```powershell
./script/verify-task19-e2e.ps1
./script/verify-task19-faults.ps1
./script/verify-task19-security.ps1
```

三个入口只要求 Docker 与 Compose。应用、Maven、Python、pnpm、Playwright 和扫描器均在固定镜像中
执行。原始大报告进入忽略的 `target/task19-*`；仓库只保存脚本、规则和摘要文档。所有等待由健康检查、
HTTP/SSE 事件、Awaitility 或带截止时间的轮询完成，禁止固定等待业务结果。

## 演练矩阵

| 场景 | 初始事实与注入点 | 不变量与观察点 | 恢复与最终事实 |
|---|---|---|---|
| redis-seckill 断连 | 活动有效、新 User 尚无该意图；只 pause 本 project 的 `redis-seckill` | 脱敏 503；同一 key 安全重试；MySQL/Redis 无第二预约或订单 | unpause 后轮询健康；202、replay=true、一个预约/订单 |
| Stream commit 边界 | Redis entry 已接收、订单尚未持久化；before commit / commit 后 ACK 前 failpoint | XPENDING 可 claim；`seckill_event_processing`、订单、库存、Outbox、审计至多一个效果 | 新 consumer 由 Awaitility 驱动 claim/reconcile；PEL 归零且 Redis/MySQL 收敛 |
| RabbitMQ 断连 | 业务事务准备提交；broker outage/confirm crash | 订单与 NEW Outbox 同事务；恢复前不伪造 SENT；重复 delivery 被 Inbox/唯一键去重 | broker 恢复并轮询 publisher；最终 SENT/PUBLISHED 和一个下游效果 |
| MySQL 短暂不可用 | Redis 已预留或 Rabbit delivery 未 ACK；事务提交前断库 | 无订单/库存/支付/Outbox 半组合；delivery/pending 保持可重试 | 恢复 DB，consumer/reconcile 收敛为一个订单或一次合法补偿 |
| Portal/Task 重启 | 已有 timeline cursor；停止 Portal；停止 Task 后再接受一个 Redis 预约形成 pending work | SSE 新请求带 Last-Event-ID；Task 停止期间无订单，重启后已处理消息无第二效果；scheduler/consumer 恢复 | readiness 与事件轮询；MySQL 中该预约恰有一个订单，timeline 业务事实保持单例 |
| 支付终态竞争 | PENDING payment、唯一 nonce、OPEN order；成功/失败/timeout/重复/迟到并发 | 只允许一个合法终态；ledger/nonce/audit/Outbox/timeline 一致；库存至多恢复一次 | Awaitility 等终态；迟到事件可记录但不能回退订单 |
| Agent/Qdrant 故障 | Agent 可用且知识已索引；分别 stop Agent 和 Qdrant | Agent 故障不影响目录/订单；Qdrant 故障只降级 RAG，动态 Java 工具仍工作；无真实 provider fallback | 启动本轮容器并轮询 ready；FakeModel/deterministic 策略不变 |

详细机器记录位于 `fault-matrix.json`，每项包含 `initialFacts`、`injection`、
`expectedInvariants`、`observability`、`finalFacts`、`recovery` 和 `evidence`。其中真实 Compose 浏览器覆盖
Redis/Portal/Agent/Qdrant；数据库提交前后、RabbitMQ、MySQL 和支付竞争复用现有 Testcontainers 精确
failpoint 测试。`finalFacts` 分别列出 MySQL、Redis、RabbitMQ 结论及其动态断言来源；矩阵是实际 gate
结果和真实测试断言的索引，不伪装成另一次数据库 dump。脚本不会把粗粒度 stop 冒充精确
commit-boundary 演练。

## 恢复、取证与清理

发生失败时先保留退出码和脱敏输出，再尝试日志/事实查询；查询或日志失败不能跳过 finally。清理按启动
后记录的精确对象逐个执行：每个容器/卷/网络必须仍带相同 project label，每个 image tag 必须仍解析到
记录的 image ID，临时目录必须位于 `target/task19-temp/<project>`。不满足身份条件时拒绝删除并报告附加
清理失败。

E2E 与 Security 入口成功和失败都生成顶层 `cleanup.json`；Fault 入口汇总 Compose 子入口与 Java
Testcontainers 的两份 cleanup 证据为自己的 `cleanup.json`。它们证明本轮容器、卷、网络、临时密钥
目录和 owned image tag 为零。
若 cleanup 自身失败，保留原业务错误并附加 cleanup 错误；人工只可按 `cleanup.json` 中的精确 ID 复核，
不得执行 `docker system prune`、全局 volume prune 或模糊 project 删除。
