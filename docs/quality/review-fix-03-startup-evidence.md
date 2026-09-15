# 对账启动边界补充：已装载活动尚无 consumer group

## 原因与局部修复

真实 loader 会在消费者启动前注册活动。此时活动可能尚无 Stream，也可能已有接受记录但 consumer group 尚未创建。原 runBatch 在事件与库存检查后调用 XPENDING，Redis 返回 NOGROUP，导致整次对账抛错、活动轮转 checkpoint 无法推进。这是联合测试暴露的实际启动边界缺陷。

修复仅包围有 COUNT 上限的 XPENDING 调用：只有最底层 `RedisCommandExecutionException` 且错误码前缀为 `NOGROUP ` 时返回空 PEL；其他异常原样传播。无 group 表示无 PEL，不表示无事件；事件逐条检查和库存分页扫描照常完成。不添加 XINFO groups 全量预读，也不让审计器代消费者创建 group。

## 先红后绿

纯回归提交：`b47af2f`。实现提交：`40c93be`。保留原有 21 项测试，仅新增两项：

- `review04LoadedActivitiesWithoutConsumerGroupStillAdvanceBoundedAudit`：真实 MyBatis + FlashSaleActivityLoader 装载两个活动，完全不调用 consumer.refreshStreams；一个活动尚无 Stream，另一个活动已有 5 条事件但无 group。B=2、6 次轮转必须检查全部 5 条事件，并查出最后一条损坏预占事实的实际 CRITICAL issue；验证消费者组仍未创建。
- `review04PendingWrongTypeStillPropagatesRedisFailure`：真实 Redis XPENDING 在错误类型 key 上返回 WRONGTYPE，仍向调用者传播 DataAccessException，防止修复吞掉其他 Redis 错误。

在纯回归提交上使用下列入口运行，结果 **2 tests, 0 failures, 1 error, 0 skipped**：启动边界用例抛出实际 NOGROUP，WRONGTYPE 传播用例通过。失败摘录：[ir03-startup-before.txt](review-fixes-2026-09-15/ir03-startup-before.txt)。

```powershell
docker run --rm --name hotshop-review03-startup-red -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --mount type=bind,source=D:/Codex/Projects/hotShop-review-03,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -pl task -am '-Dtest=SeckillOrderReliabilityContainerTest#review04LoadedActivitiesWithoutConsumerGroupStillAdvanceBoundedAudit+review04PendingWrongTypeStillPropagatesRedisFailure' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

红测期间 MySQL 初始化后的宿主转发连接等待持续约 76.57 秒，然后自行恢复。MySQL 日志已 ready，后续 Windows localhost 与 runner 内 host.docker.internal 连通性只读核对通过；未修改代码、超时或网络配置。该启动延迟不是业务失败证据，真正红测是随后执行的 NOGROUP。

修复后在 `40c93be` 使用完全相同测试参数重跑（runner 名改为 `hotshop-review03-startup-green`）：**2 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS，退出码0**。2026-09-15 15:23:52 UTC，总耗时01:20。摘录：[ir03-startup-after.txt](review-fixes-2026-09-15/ir03-startup-after.txt)。

两轮均使用隔离 MySQL 8.0.46 与 Redis 8.8.1，11条 Flyway migration 实际执行；未增加睡眠、放宽超时或关闭检查。自己的 MySQL/Redis/Ryuk/runner 已删除；完整日志保留于本 worktree 的 `task/target/review-fix-03/startup-red.log` 与 `startup-green.log`。未修改旧复核报告或旧 red patch。

该局部变更后的原21项及全量联合验证由主agent在最终集成HEAD重跑；本补充证据仅声明新增2项定向通过。
