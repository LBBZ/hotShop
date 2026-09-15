# IR-03 / IR-04 修复与验证

基线：`a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`。工作分支：`review-fix-03-reconciliation`。
依赖库存分支原提交 `5150684`（fast-forward 合入，未复制提交）。本报告只覆盖本次受影响链路，不表示整项目终验。

## 修复前真实失败

先仅添加两项正式 JUnit 回归，然后用基线生产源码运行（MySQL 8.0.46、Redis 8.8.1 Testcontainers，隔离临时容器）：

```powershell
docker run --rm --name hotshop-review03-red -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --mount type=bind,source=D:/Codex/Projects/hotShop-review-03,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -pl task -am '-Dtest=SeckillOrderReliabilityContainerTest#review03*+review04*' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：2 tests, **2 failures, 0 errors, 0 skipped**，退出码 1。原始失败摘录：[ir03-ir04-before.txt](review-fixes-2026-09-15/ir03-ir04-before.txt)。

- `review03OrdinaryPurchaseAfterActivityLoadDoesNotRaiseConservationIssue`：真实 ProductMapper.reduceStock 后商品库存 99，活动装载快照 100，对账实际插入 `MYSQL_STOCK_CONSERVATION_VIOLATION / CRITICAL`，证据 `catalogEquationHolds=false`。此红测验证真实 Mapper 与对账 issue；完整普通订单/后台调整/超时服务链另由联合测试验证。
- `review04HistoricalReservationsRespectActualRedisReadBudget`：37 条历史，B=3，Redis commandstats 实际 HGETALL 增加 **41**，超过测试预算 16；不是仅检查 report.checkedEvents。
- 首次命令中 PowerShell 将未引用的 `-Dsurefire.failIfNoSpecifiedTests=false` 拆分，Maven 在测试前拒绝。这是命令配置错误；上列引用后的两项业务断言失败才计为红测证据。

## IR-03 守恒边界

商品库存与活动配额各自拥有独立账面余额。IR-01 的新增 V1_9 迁移在执行时以存量实际库存建立基准，所有合法扣减、回补、后台 delta 调整都在同一 SQL/事务中对 actual 和 expected 加相同 delta。新增商品/活动以首次 INSERT 库存为基准，后续 UPDATE 绝不从 actual 重新赋 expected。

对账用一次 SELECT 读取 `catalog_product.stock / expected_stock` 和 `flash_sale_activity.available_stock / expected_available_stock`。普通订单只改变商品账；秒杀建单与秒杀超时同时改变商品账和对应活动账；普通取消仅改变商品账；后台调整仅改变商品账并审计。同商品其他活动、不同装载时点不共享库存快照。无需汇总快照前所有订单，也不会把多活动历史重复计算。直接篡改 actual 仍产生 CRITICAL，并保留 actual/expected 证据，不自动修补库存差额。

迁移只建立迁移时点的基准，不能追溯辨认迁移前已存在的账实异常。篡改 actual 与 expected 两份事实同时一致不在本检测能力内。

秒杀 Redis 是活动配额投影，基准为该活动装载的 `initialAvailableStock`。有效数量是 RESERVED / ORDER_CREATED / COMPENSATING 数量；COMPENSATED 和 PAYMENT_EXPIRED 不再占配额。数据库 CANCELED 保留订单、明细与 order_id 是合法事实，反向审计已修正；Redis 异步尚未收到超时投影期间，两个存储分别按自己的事实守恒。

`initialCatalogStock` 保留用于兼容旧装载元数据，已退出商品守恒及同版本装载的库存相等要求。

## IR-04 实际工作预算与恢复

一次 runBatch 只轮转推进 **一个活动**，即使它为空或无效也消耗该活动的轮次；通过新 ZSET 的 lex 范围 `LIMIT 1` 发现，禁止读取完整注册表。B 为 reconciliation-batch。

- Stream 事件审计至多 B 条，所有 XRANGE 带 COUNT。
- 全量 Redis 守恒独立推进至多 B 条：原子 Lua 内 XRANGE COUNT B，每条固定 HMGET 五个字段，无全量历史集合或 HGETALL 历史遍历。
- PEL 审计至多 B 条，持久化独立游标，避免前 B 条未终态长期遮挡后续终态。
- MySQL 反向候选先 keyset LIMIT B 再关联事实；订单 orphan 扫描独立 keyset LIMIT B；历史普通 reservation 也消耗游标页，不能因为过滤而无界跳读。
- 每个反向 reservation 每轮只读取 1 条 processing 候选与至多 1 条精确 Stream 记录，持久化 processing_id 游标。只有候选全部穷尽才报 missing，不会因为第 3 条才有真实证据误报。V1_10 添加 `(reservation_no, processing_id)` 索引，避免按状态混合排序整段历史。
- 默认只读模式所有 XRANGE 返回记录合计上限 4B（事件 B、守恒 B、反向 B、PEL B）；reservation HGETALL 上限 2B（事件/PEL），守恒 HMGET 上限 B，另有一个活动元数据 hash。索引发现至多两次 LIMIT 1（末尾回绕）。修复模式每个候选最多执行既有的固定字段 Lua/受证据约束修复，没有历史循环。

Redis checkpoint 持久化 cursor、累计数量、fence、state、重启次数、完成次数。`inventoryRevision` 在接受预占、补偿、PAYMENT_EXPIRED 的有效状态变化之前原子递增；幂等重放不增。活动重新装载的 databaseVersion 参与 fence。任一分页期间合法写入变更 fence，下一页丢弃旧累计从头重启；只有完整同一 fence 扫描结束才比较库存。Lua 每页读取和状态更新同一次原子执行，进程重启不会遗失游标。MySQL checkpoint 同步记录 `IN_PROGRESS/COMPLETE;fence;scanned;restarted` 供现有后台检查。

持续写入可能使一个活动的全量扫描持续 IN_PROGRESS，这不是通过；可从 checkpoint 的 restarts 看到。事件逐条审计和其他活动继续公平前进。获得足够稳定窗口后全量检查完成并保留真正异常。Redis 数据持久化丢失会从头重建完整扫描，不能继承丢失前“通过”结论。滚动升级须先更新所有改变配额的 Lua 调用方，避免旧 writer 不递增 revision。

## 旧 registry 显式升级

新 loader 原子同时维护原消费者 SET 和新审计 ZSET。runBatch 比较 SCARD / ZCARD；不相等产生 `RECONCILIATION_INDEX_UPGRADE_REQUIRED / CRITICAL`，不把空索引当作无活动通过。旧 SET 保留，数据库已删除的孤儿 Stream 仍进入审计索引。

显式迁移工具（不会由每次对账偷偷执行）：

```powershell
./script/upgrade-reconciliation-index.ps1 -RedisContainer <明确指定的redis-seckill容器名> -CountHint 100 -MaxPages 10 -MaxSeconds 10
```

每步仅一次 SSCAN，将返回条目 ZADD 和 cursor 原子持久化；工具输出实际 visited 数，可重复运行至 COMPLETE，不移除原 SET。**SSCAN COUNT 是软提示**，此一次兼容升级不承诺硬记录预算；MaxSeconds 在两次 Redis 调用之间检查，不能抢占一条正在运行的命令。日常 runBatch 绝不调用此升级扫描，使用严格 LIMIT。升级中 loader 双写，完整 SSCAN 保留旧成员并捕获新成员；完成后核对两者 cardinality，已有升级告警需按正常流程人工确认解决。

## 验证状态

- 两项基线业务失败已执行并保留。
- 编译检查已执行一次成功；实现追加测试后的编译/真实绿测正在执行，结果完成后补写。
- 新增正式测试包含实际 Redis SLOWLOG 的 XRANGE COUNT（含 Lua）、commandstats HGETALL/HMGET、74 条历史与 B=3 多轮完成、服务实例重建恢复、两活动公平、跨页真实补偿与新预占、篡改报警证据、160 个旧 SET 成员显式升级与孤儿检测、第三条 processing 候选才有证据。
- 原有真实秒杀故障矩阵以及 PAYMENT_EXPIRED 投影受 Lua keys 变更影响，完成后补记实际执行范围。
- 不运行根 Maven clean；所有容器仅 Testcontainers 临时容器，不触碰既有数据卷、无全局 prune、无远端发布。
