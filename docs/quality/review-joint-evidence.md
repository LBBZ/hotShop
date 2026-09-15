# 库存调整与对账联合真实服务回归

## 测试装配和边界

`admin/src/test/java/com/real/admin/InventoryReconciliationJointContainerTest.java` 使用独立 Testcontainers MySQL 8.0.46、Redis 8.8.1-alpine，真实 Flyway、MyBatis、Spring 事务代理，以及生产服务：

- MockMvc `AdminProductController` → `AdminProductAuditService` → 商品 Mapper 与真实 `JdbcAuditLogWriter`。
- `OrderStateService` 普通购买，`FlashSaleActivityLoader` 与 `FlashSaleReservationService` 生产 Lua，`ReservationStreamConsumer` → `SeckillProcessingService` 秒杀建单。
- `OrderTimeoutService` 两类订单超时与幂等回放，真实 Outbox → `SeckillPaymentExpiredProjectionConsumer` → Redis Lua 投影与重复投影。
- `SeckillReconciliationService` 有界批次（B=3），两活动不同时间装载、合法库存变更零问题记录、真实篡改 CRITICAL 及实际 evidence JSON。

只有 Rabbit Channel/RabbitTemplate 传输被替换，生产事件解析和库存投影真实执行，验证两次 ACK 且没有重试发布；此测试没有验证 Rabbit 真实投递。MockMvc 显式提供管理员身份，未经过认证过滤器与浏览器。为避免等待付款窗口，仅将测试订单 deadline 调到过去，再构造与实际数据库事实匹配的超时事件；没有修改库存、订单终态或 expected 余额来模拟业务成功。

创建 100 → 调整 +10 → 普通购买 2 → 秒杀购买 3 → 普通回补 2 → 秒杀回补 3，库存依次为 100/110/108/105/107/110。每次提交旧资料 HTTP 表单后读取实际 stock 与 expected_stock。第二活动在普通购买后装载，验证与首活动的快照差异不制造告警。秒杀 MySQL 已取消但 Redis 尚未投影时，以及投影完成后，均检查实际 issue 表为空。审计核对 delta、before/after、version、reason 与两次回补记录；旧调整版本返回可理解的 409。

最后只篡改 stock 为 111，expected_stock 仍为 110；检查真实 issue_type、CRITICAL、catalogStock=111 与 expectedCatalogStock=110，库存不被自动修复。

## 修复前红测

在基线 `a3e7a6c55040bbebc8760750af4b5cfcfd0406aa` 上先执行新正式回归，再合入功能代码：

```powershell
docker run --rm --name hotshop-review-joint-red --mount type=bind,source=D:/Codex/Projects/hotShop-review-joint,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -ntp -pl admin -am '-Dtest=InventoryReconciliationJointContainerTest#staleHttpFormCannotUndoRealOrdinaryOrder' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 55.31 s**；断言 `JSON path "stock" expected:<99> but was:<100>`。此处保存实际输出摘录，不提交完整 Maven 构建日志。这比原诊断 SQL 多覆盖了真实 HTTP DTO、事务、MyBatis 与订单业务服务。

初次命令中未引用 `-Dsurefire.failIfNoSpecifiedTests=false`，被当前 PowerShell 拆分，Maven 报未知 phase `.failIfNoSpecifiedTests=false`；加单引号后才得到上述真实业务失败。该命令配置错误仅在此摘要记录，不计为回归失败。

## 修复后绿测

### 集成发现并修正的附加缺陷

主 agent 首次在合并后的 `d534c86` 执行全 reactor `verify -fae`，联合类为 **3 tests / 2 failures**：调整 +10 响应期望 110、实际 100，审计故障撤除后重提 +5 响应期望 105、实际 100。根因是 `AdminProductAuditService` 的 JDBC 调整没有清除 MyBatis 事务一级缓存，第二次 `requireProduct` 返回调整前对象，成功审计也取到旧 after 值。修复 `92f7c2d` 将该操作的前后快照均经同一 JDBC 事务查询，使用 FOR UPDATE 当前读，保留原 CAS、审计及所有库存断言；没有全局关闭 MyBatis 缓存。

最初局部复跑（加 FOR UPDATE 前的同等 JDBC 读路径）得到 **3 tests / 0 failures / 1 error**，真实审计存储失败回滚测试及陈旧普通订单表单测试均通过。完整业务测试通过调整响应后，在首次对账遇到 `XPENDING NOGROUP`：新活动已由生产 loader 注册，但生产 consumer 尚未发现并创建 group。未通过在测试中提前 refresh consumer 绕开；为对账的这一合法启动窗口另补正式红绿回归及局部处理。

原始失败摘要见 [首次联合失败](review-fixes-2026-09-15/integration-joint-before.txt) 和 [启动窗口错误](review-fixes-2026-09-15/integration-joint-intermediate.txt)。完整原始日志保存在集成 worktree 的 `target/review-fixes/integration-java-third-attempt.log`、`joint-cache-fix.log`。最终完整通过结果在联合修复合并后补记。

## 复验命令与资源

在最终集成 worktree 中运行同样 Docker 命令，将目录改为集成目录，测试参数改为 `'-Dtest=InventoryReconciliationJointContainerTest'`。使用项目固定 Wrapper Maven 3.9.16、Temurin Java 21，未使用宿主的 Java 17/Maven 3.9.9。Flyway 使用全新测试数据库，无诊断 SQL 接触用户数据。

不执行 clean、install、push、master 合并或全局 Docker prune；Maven 依赖缓存为现有共享 `hotshop-task04-m2`，不安装 SNAPSHOT。Testcontainers 自动回收本类创建的 MySQL/Redis，测试关闭自身 Lettuce 与 metrics，Maven 执行容器 `--rm`；不清理无关容器或卷。功能分支联合测试不替代主 agent 在最终集成 HEAD 的复验。
