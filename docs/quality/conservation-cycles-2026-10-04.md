# 2026-10-04 后台守恒扫描审计

[证据索引](evidence-index.md) · [结构化结果](conservation-cycles-2026-10-04.json) · [扫描协议](../architecture/stream-order-processing.md#有界守恒扫描) · [ADR-010](../architecture/adr/ADR-010-bounded-conservation-cycles.md)

本轮接续活动重载工作，审计后台对账在持续写入及历史增长时的推进与空间边界。
基线为 `318053320b3471eda38004eb711b3109e3d7179c`，实现提交为
`2aed7c3cbc09fdc2fd5f81324d403969341c3274`。日期采用 Asia/Shanghai。
跨模块验证补充提交为 `d5350840144aec3f5ca06f287ffead754b8c26e2`，只调整测试断言，运行镜像源码仍为上述实现提交。

## 发现与处理

| 条件 | 原有行为 | 修正 |
| --- | --- | --- |
| 每两页之间有新预约或释放 | 库存 fence 变化后从第一页重扫，反复检查同一前缀 | 每轮捕获固定 Stream 尾部，游标继续到该终点；写入干扰使本轮结论失效，但不会重置游标 |
| 活动有大量不同的有效预约 | `XRANGE COUNT` 限制每页读取，seen hash 总大小仍随预约增长 | 每活动每轮最多保留配置数量的不同有效预约，默认 10,000，达到预算后明确停止核验 |
| 任务不再继续访问未完成扫描 | 无 TTL 的去重表持续保留 | 每页为 seen 续期 24 小时，空闲过期；缺失去重状态后重新扫描，不沿用部分和 |
| 扫描无法获得稳定余额 | 只记录反复 `IN_PROGRESS`，未形成明确终止原因 | 记录 `INCONCLUSIVE` 与原因，产生 WARNING 和有界标签指标 |
| 跨页重复投递 | 旧实现已按 reservationNo 去重 | 保留逐投递校验及单次计数，重复事件也不额外消耗去重名额 |

只在完整且 fence 不变的一轮中判断库存等式。`COMPLETE` 是扫描完成，仍可能发现 CRITICAL 库存差异；
`INCONCLUSIVE` 是无法核验，不能解释为库存正确或库存损坏。
默认 dry-run 与自动修复开关不变，没有库存调整、订单操作、数据库迁移、预约写脚本修改或 Stream 截断。

实现入口为[分页 Lua v2](../../task/src/main/resources/redis/reconcile-conservation-page-v2.lua)、
[对账服务](../../task/src/main/java/com/real/task/seckill/SeckillReconciliationService.java)和
[配置](../../task/src/main/java/com/real/task/seckill/SeckillOrderProperties.java)。原 Lua v1 保留。
Compose、应用配置和 `.env.example` 接入 `HOTSHOP_SECKILL_RECONCILIATION_MAX_RESERVATIONS`，值必须为正数。
旧 checkpoint schema 和缺失 seen 都会有界重启；终止一轮使用 `UNLINK` 分离去重表。

## 红绿回归与证据

新增 **7 项**回归：5 项独立 Redis Lua 检查，2 项真实 MySQL / Redis 对账服务检查。
先在基线生产代码上执行其中 6 项，结果为 **1 通过、5 失败、0 错误、0 跳过**：

- 持续写入后游标仍等于第一页末尾，没有继续推进。
- 去重数量达到测试上限 3 后仍继续聚合。
- 调低上限后仍继续使用超额的去重表。
- seen 的 TTL 为 `-1`，没有空闲过期。
- 后台 checkpoint 仍显示重启中的 `IN_PROGRESS`，没有无法核验的原因。

重复投递在上限边界跨页去重的用例在旧实现也通过，作为保留行为的回归。
服务层配置上限与 WARNING 落库的第 7 项在实现后加入，未将其计作基线红测。

修复后，`ConservationScanContainerTest` **5 项**、`SeckillOrderReliabilityContainerTest` **31 项**，
共 **36 项通过，0 失败、0 错误、0 跳过**，结束于 `2026-10-03T17:31:29Z`。
这包含既有的数据库 / Redis 中断恢复、补偿崩溃恢复、跨页去重、旧 checkpoint 兼容、服务重启、活动轮转和实际命令读取预算检查。
两项原有 fence 重启断言按新的“先结束为无法核验，再开始稳定轮次”协议更新；库存数量与真实篡改检测断言保留。

首次[托管 CI](https://github.com/LBBZ/hotShop/actions/runs/37141433745)在 Admin 联合测试失败，其他组件门禁通过。
本地最初的 36 项范围未覆盖该类：超时投影改变了进行中的扫描，新协议保留一条 `WRITES_DURING_SCAN` WARNING，
而旧断言仍要求全部 issue 为空。没有通过关闭告警或忽略所有 WARNING 处理此失败。
补充断言要求全部 issue 恰好是指定类型、级别与原因的那一条，同时校验后续完整扫描的 fence 等于当前库存版本、
有效数量为 0、无效事实为 0；原有商品库存、审计与后续真实篡改断言继续执行。
Admin 完整联合类 **6 项通过，0 失败、0 错误、0 跳过**，结束于 `2026-10-03T17:53:25Z`。
本轮相关本地验证因此为两次运行合计 **42 项通过**，保留首次 CI 失败及补充测试日志。

新服务回归证明：连续追加两次预约后，一轮按原终点结束并写 WARNING；停止写入后，新一轮检查全部 6 个预约，
之后人为增加 Redis 库存 1，仍产生 CRITICAL 差异且不自动改回库存。另一个服务用例把上限设为 2，验证配置实际传入 Lua、
WARNING 可查询、seen 已释放，库存与订单数保持原样。

Lua 独立用例使用最小库存 / 事件夹具，验证扫描算法，不宣称执行了完整预约入口。
过期检查验证正 TTL 并模拟表缺失，不等待真实 24 小时。容量上限使用小配置触发边界，没有把这些用例描述成 10,000 用户压力或长期运行实验。

测试在 Windows Docker Desktop 的 Java 21.0.11 容器中执行，Maven Wrapper 3.9.16、MySQL 8.4.11、Redis 8.8.3-alpine；
容器限额 2 GiB，JVM 为 `-Xmx512m -XX:ActiveProcessorCount=4`，Testcontainers 使用 `host.docker.internal`。
绿测日志保留 Redis / MySQL 连接失败与注入异常：对应既有中断恢复测试，最终断言通过，不作为本轮未解释的生产故障。
公开 JSON 只收录筛选后的统计与版本，原始日志和 XML 保留于 `.local/verification/conservation-cycles-20261004/`。

## 复现与运行边界

在具备 Java 21、Docker 与依赖的环境执行：

```powershell
./mvnw -B -pl task -am '-Dtest=ConservationScanContainerTest,SeckillOrderReliabilityContainerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
./mvnw -B -pl admin -am '-Dtest=InventoryReconciliationJointContainerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
node script/check-docs.mjs
```

文档链接与 Compose 配置校验通过。Task 镜像基于上述实现提交和干净工作区构建；运行核验与镜像标识记录于配套 JSON。
本轮没有修改页面或公共 DTO，不复用上一轮浏览器结果作为本轮验收。托管 CI 应以最终合并提交的具体运行判定。

验收环境与主演示使用同一 Task 镜像，健康检查均为 `UP`；已有活动 913001 的 checkpoint 从 schema 2 升级为 3，
并观察到新一轮 `COMPLETE`、`lastCompletedInvalid=0`。这里只证明新扫描脚本在现有环境被调度执行，不把单个 checkpoint 当成整个演示数据集无异常的证明。
主 Web 入口 HTTP 200，没有重置数据库、预约或业务库存。

运行核验还观察到既有消费者持续记录空闲轮询超时。演示 `HOTSHOP_SECKILL_ORDER_READ_BLOCK=2s`，
[Redis 连接配置](../../infrastructure/src/main/java/com/real/infrastructure/Redis/RedisConnectionsConfiguration.java)采用的默认命令超时也是 2 秒；
阻塞读取与客户端截止时间没有余量。此轮未改动这条消费配置，已单独加入后续工作；健康检查 `UP` 不代表没有这类运行告警。

固定终点保证追加事件不会无限延长同一轮范围，前提是持续获得调度且观察状态可用；不能保证并发写入期间获得一致库存快照。
后续需要独立的快照或变更流水协议，才能证明持续写入时的完整守恒。超过去重预算的活动仍需分段或离线核验，重复执行不会使其自动通过。
单轮工作量仍随保留历史增长，原始 Stream 未归档；空间上限按活动计算，包含一个额外初始化标记，不能等同于全局内存或固定字节预算。
`UNLINK` 的物理回收异步发生。后续清洁扫描不自动关闭既有 WARNING，沿用原有 issue 处理流程。
