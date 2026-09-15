# IR-03 / IR-04 修复与验证

基线：`a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`。工作分支：`review-fix-03-reconciliation`。
依赖库存分支原提交 `5150684`（fast-forward 合入，未复制提交）。本报告只覆盖本次受影响链路，不表示整项目终验。

## 修复前真实失败

先仅添加两项正式 JUnit 回归，然后用基线生产源码运行（MySQL 8.0.46、Redis 8.8.1 Testcontainers，隔离临时容器）：

```powershell
docker run --rm --name hotshop-review03-red -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --mount type=bind,source=D:/Codex/Projects/hotShop-review-03,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -pl task -am '-Dtest=SeckillOrderReliabilityContainerTest#review03*+review04*' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：2 tests, **2 failures, 0 errors, 0 skipped**，退出码 1。原始失败摘录：[ir03-ir04-before.txt](review-fixes-2026-09-15/ir03-ir04-before.txt)。另提供可在报告基线应用的[仅回归测试补丁](review-fixes-2026-09-15/ir03-ir04-regression-before.patch)，对基线原文件执行 `git apply --check` 已通过；可在隔离基线 worktree 应用后使用上列命令重现，不能把该红测退出码当修复通过。

- `review03OrdinaryPurchaseAfterActivityLoadDoesNotRaiseConservationIssue`：真实 ProductMapper.reduceStock 后商品库存 99，活动装载快照 100，对账实际插入 `MYSQL_STOCK_CONSERVATION_VIOLATION / CRITICAL`，证据 `catalogEquationHolds=false`。此红测验证真实 Mapper 与对账 issue；完整普通订单/后台调整/超时服务链另由联合测试验证。
- `review04HistoricalReservationsRespectActualRedisReadBudget`：37 条历史，B=3，Redis commandstats 实际 HGETALL 增加 **41**，超过测试预算 16；不是仅检查 report.checkedEvents。
- 首次命令中 PowerShell 将未引用的 `-Dsurefire.failIfNoSpecifiedTests=false` 拆分，Maven 在测试前拒绝。这是命令配置错误；上列引用后的两项业务断言失败才计为红测证据。

## IR-03 守恒边界

商品库存与活动配额各自拥有独立账面余额。IR-01 的新增 V1_9 迁移在执行时以存量实际库存建立基准，所有合法扣减、回补、后台 delta 调整都在同一 SQL/事务中对 actual 和 expected 加相同 delta。新增商品/活动以首次 INSERT 库存为基准，后续 UPDATE 绝不从 actual 重新赋 expected。

对账用一次 SELECT 读取 `catalog_product.stock / expected_stock` 和 `flash_sale_activity.available_stock / expected_available_stock`。普通订单只改变商品账；秒杀建单与秒杀超时同时改变商品账和对应活动账；普通取消仅改变商品账；后台调整仅改变商品账并审计。同商品其他活动、不同装载时点不共享库存快照。无需汇总快照前所有订单，也不会把多活动历史重复计算。直接篡改 actual 仍产生 CRITICAL，并保留 actual/expected 证据，不自动修补库存差额。

迁移只建立迁移时点的基准，不能追溯辨认迁移前已存在的账实异常。篡改 actual 与 expected 两份事实同时一致不在本检测能力内。

秒杀 Redis 是活动配额投影，基准为该活动装载的 `initialAvailableStock`。有效数量是 RESERVED / ORDER_CREATED / COMPENSATING 数量；COMPENSATED 和 PAYMENT_EXPIRED 不再占配额。数据库 CANCELED 保留订单、明细与 order_id 是合法事实，反向审计已修正；Redis 异步尚未收到超时投影期间，两个存储分别按自己的事实守恒。

`initialCatalogStock` 保留用于兼容旧装载元数据，已退出商品守恒及同版本装载的库存相等要求。Java 初装校验 `totalStock <= catalogStock` 仍保留：如果活动原配额正好等于商品初始库存，普通购买降低商品库存后再调用同版本 load，仍可能在该校验处返回 ACTIVITY_INVALID。本轮没有扩大初装/重载业务规则，也不宣称所有重载边界已通过；联合测试验证的是不同活动在不同时间以合法额度装载。

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

## 修复后执行结果

生产与测试实现 SHA：`9247ae4223b4cdbb95e7337bcb644d075258f38e`。该 HEAD 在本功能分支实际执行完整受影响类：

```powershell
docker run --rm --name hotshop-review03-green -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --mount type=bind,source=D:/Codex/Projects/hotShop-review-03,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -pl task -am '-Dtest=SeckillOrderReliabilityContainerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**21 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS，退出码 0**。2026-09-15 14:52:20 UTC，Maven 总耗时 02:35，测试耗时 120.9 秒。包括原有 15 项和新增 6 项。MySQL 实际成功迁移全部 11 条（含 V1_9/V1_10），LATERAL LIMIT 和有界索引查询均真实执行成功。摘录：[ir03-ir04-after.txt](review-fixes-2026-09-15/ir03-ir04-after.txt)。

实际 Redis 取证输出：

```text
REVIEW_BUDGET B=3 HGETALL=4 HMGET=3 XRANGE_COUNT_SUM=6 XRANGE_COMMANDS=2
```

测试断言 HGETALL <= 2B+1、HMGET <= B、SMEMBERS 增量为 0；SLOWLOG 捕获的 XRANGE 数量必须等于 commandstats 增量，必须包含 Lua 内 `(0-0` 范围，捕获不能达到 128 项截断上限。所有 XRANGE 的 COUNT 总和 <= 4B，构成实际返回记录的保守上界，不是只验证单个分页命令。

新增正式回归：

| 测试 | 真实验证事实 |
| --- | --- |
| review03OrdinaryPurchaseAfterActivityLoadDoesNotRaiseConservationIssue | ProductMapper 扣减后实际 MySQL issue 表无库存误报 |
| review04HistoricalReservationsRespectActualRedisReadBudget | 37 历史/B=3，核对实际 Redis 命令和整体预算 |
| review04PagesResumeAfterServiceRestartAndRotateAcrossActivities | 两活动共 74 历史、B=3、多次运行完整覆盖，重建服务恢复，checkpoint 各完成总量37，实际 issue 表为空 |
| review04CompensationBetweenPagesRestartsSnapshotAndStillFindsRealTampering | 真实补偿 Lua 与新预占改变 fence，累计重启；同用户新 slot 合法；稳定后全量无误报，随后 Redis stock 篡改生成 CRITICAL 及数量证据，不修补该差额 |
| review04LegacyRegistryUpgradeIsExplicitResumableAndPreservesOrphanStreams | 160 个旧 SET 成员，空索引明确告警；一次升级步进可恢复，成员全部保留，孤儿活动仍受检 |
| review04ReverseEvidenceContinuesPastTwoInvalidCandidatesWithoutFalseIssue | 第三个 processing 候选才有真实 Stream 证据，前两次分页不错误断言 missing |

第一次修复后整类运行是 **21 tests / 0 failures / 1 error**，错误来自新取证夹具用 Spring Redis 原始 execute 读取 SLOWLOG 的嵌套整数结果，默认 ByteArrayOutput 不支持 long。该次不算通过，保留[原错误摘录](review-fixes-2026-09-15/ir03-ir04-first-green-attempt.txt)。改为经过本地 jar javap 核对的 Lettuce typed slowlogGet/Reset 后重跑全部 21 项，得到上述通过结果；没有删除命令检查或放宽预算，反而将最初红测上限16强化为正式预算公式7。

编译入口（两次已成功）：`sh ./mvnw -B -pl task -am -DskipTests test`，使用同一 Java21 容器与 Maven3.9.16 wrapper。Windows PowerShell 的 Maven `-D` 含点参数需整体引用；容器日志和业务时间采用 UTC，宿主时区 Asia/Shanghai。新增/修改文本已严格 UTF-8 解码检查，报告显式写 UTF-8/LF；证据仅清理尾部空白以通过 diff --check。

## 资源与尚未执行范围

- 本分支两轮整类 green 尝试和一轮 red 的 MySQL/Redis/Ryuk 临时容器已由 Testcontainers 删除，green runner 使用 --rm 删除。最终 `docker ps` 中仅剩其他任务的 `hotshop-ir01-http`，未操作它。
- 不运行根 Maven clean；完整运行日志保留在本 worktree 的 `task/target/review-fix-03/`。Maven 缓存 `hotshop-task04-m2` 是既有共享依赖缓存，未删除。
- 本功能分支没有完整运行其余全部 Java、真实 Qdrant、浏览器闭环、长时压测。PAYMENT_EXPIRED 的既有 Rabbit+Redis 测试类只调整了新增 metadata key 所需种子，最终重跑交由主 agent 的集成验证；这不是本分支已通过项。
- 普通/秒杀订单、后台显式调整、真实超时回补与商品/活动对账的完整联合服务验证由独立 joint 测试和主 agent 在集成 HEAD 重跑，本分支局部通过不能替代它。
- PowerShell 旧索引升级入口的真实 CLI 冒烟由主 agent 在集成分支执行；其发现并修复了裸逗号参数解析问题。此处的 JUnit Lua 升级测试不冒充 PowerShell 入口通过证据。
- 本分支无 push、master 合并、全局 prune、既有数据卷操作或付费模型调用。

## 最小独立复验

在最终集成 worktree 用上列完整 `SeckillOrderReliabilityContainerTest` 命令复跑，另运行统一修复报告指定的 joint、HTTP 与 PAYMENT_EXPIRED 测试。核对实际 issue 表断言、打印的 Redis 预算、迁移成功和最后测试汇总；不要只检查报告 checkedEvents。旧部署首次启用有界对账前按上节显式升级索引，并核对实际 CLI 的 COMPLETE 与 cardinality。
