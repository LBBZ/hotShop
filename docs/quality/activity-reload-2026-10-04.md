# 2026-10-04 活动重载与库存核验

[证据索引](evidence-index.md) · [结构化结果](activity-reload-2026-10-04.json) · [装载协议](../architecture/flash-sale-reservation.md#4-mysql--redis-seckill-装载) · [ADR-009](../architecture/adr/ADR-009-preserve-loaded-offer.md)

本轮接续[后台功能审计](admin-functional-audit-2026-10-03.md)，修正正常销售、超时回库及共享商品库存消耗之后的活动重载。
基线为 `48b87c1645cdc464acd8588629831b7499a8b2ee`，实现提交为
`9fc68d6e520d2823825612b12a4a1b32ba601de0`。镜像构建与浏览器验收时工作区干净，日期采用 Asia/Shanghai。

## 发现与修正

| 触发条件 | 原有问题 | 当前行为 |
| --- | --- | --- |
| 建单或超时回库推进活动行版本 | 装载把库存变化当成配置更新，有预约时拒绝正常重载 | 比较实际 offer 配置；相同配置返回 `IDEMPOTENT`，保留已发布版本、当前库存及预约 |
| 活动或普通订单消耗共享商品库存 | 重复加载仍要求总配额不超过当前商品余量，已售出活动被判无效 | 商品库存上限用于首次初始化及未使用配置替换；重复加载不重新分配库存 |
| 预约已经建单、补偿或超时释放 | 用全部历史预约数量核对已扣减的数据库库存，产生错误差异 | 区分历史数量、Redis 有效占用与 MySQL 已建单数量，分别检查库存守恒 |
| 同一用户释放旧预约后再次预约，或 Stream 重复投递 | 历史记录和当前 User 占位可能被混为一谈，重复事件可能重复计数 | 校验每次投递的不可变字段，按预约编号去重；释放记录不要求继续占有 User 槽位 |
| 历史 Stream 增长 | 管理请求完整 `XRANGE` 并逐记录远程读取，工作量没有上限 | Redis 单次原子观察最多 1,000 条事件；超过上限明确返回核验未完成，不执行完整范围读取 |
| Redis 元数据丢失、仅余库存，或数据库仍有历史预约 | 普通加载可能被误作恢复入口 | 拒绝重新初始化，保留现状供排障；已有预约的配额或配置变更仍拒绝 |

关键实现为[装载器](../../domain/src/main/java/com/real/domain/service/seckill/FlashSaleActivityLoader.java)、
[装载 Lua v3](../../domain/src/main/resources/redis/load-flash-sale-activity-v3.lua)和
[只读观察 Lua](../../domain/src/main/resources/redis/inspect-loaded-activity-v1.lua)。v1 / v2 文件保留，没有数据库迁移、公共 DTO 或事件字段变更。

初始可用库存允许小于活动总配额，沿用原有契约；差额不会在重载时补发。
响应中的 `databaseVersion` 是当前 MySQL 行版本，`redisVersion` 及预约事件版本仍是发布 offer 时的来源版本。
页面将不一致或未完成核验统一显示为“库存核验未确认一致”，附上服务端原因和排障入口，并把统计名称改为“已核验预约记录”。

## 真实事务回归

新增 **8 项**回归：3 项 MySQL / Redis 联合场景，5 项 Portal 装载边界检查。
联合测试调用真实建单、超时与投影服务，覆盖下面的库存变化：

| 阶段 | MySQL 活动可用量 | Redis 可用量 | 重载要求 |
| --- | --- | --- | --- |
| 初始装载 | 100 | 100 | 建立初始余额 |
| 接受数量 3 的预约 | 100 | 97 | 保留待处理预约 |
| 消费并创建订单 | 97 | 97 | 配置相同，保留 offer 版本并核验守恒 |
| 订单超时，Redis 投影尚未执行 | 100 | 97 | 不代替投影把 Redis 改为 100 |
| 超时投影完成 | 100 | 100 | 旧预约保留历史、释放占用 |
| 同一用户再次预约数量 2，并完成建单 | 98 | 98 | 历史数量为 5，有效占用为 2，余额仍正确 |

另一个联合场景先由普通订单消耗共享商品库存 97，再由两个活动各预约 2；一笔建单成功，另一笔补偿。
此时商品余量为 1，两活动重载仍保持各自余额。取消普通订单返还 97 后，再次核验仍正确。
第三个场景证明已使用活动的配额变更继续拒绝，恢复原配置后可幂等重载且预约事实不变。

Portal 边界覆盖未解释的数据库余额变更、孤立 Redis 库存、数据库历史阻止重新初始化、重复 Stream 投递，以及 1,001 条事件的上限退出。
最后一项同时检查 Redis commandstats，证明上限路径没有增加 `XRANGE` 调用。

联合测试使用真实 MySQL / Redis 容器、生产事务服务和提供身份的 MockMvc Servlet 绑定；不等于浏览器 JWT 登录的完整购买验收。
数据库历史阻止初始化用例直接构造已补偿行，不把该夹具描述为执行过完整补偿链路。

## 验证结果与失败记录

修正夹具后，3 项新增联合测试在基线代码上均失败：两项返回 `ACTIVITY_INVALID`，恢复原配置的一项返回 `RESERVATIONS_EXIST`，都不符合预期的幂等重载。
修复后，Portal 17 项、联合场景 6 项、Admin 装载服务 2 项，共 **25 项通过，0 失败、0 错误、0 跳过**。
没有在基线上执行另外 5 项 Portal 新用例，因此不将它们计作已观察到的红测。

过程中保留以下失败，没有用最终绿测覆盖原日志：

- 原生 Maven 停在依赖解析，未形成业务测试结论；初次 Docker 执行出现引擎 500 / EOF。
- 使用桥接网关作为 Testcontainers 宿主地址时无法连接 Ryuk；改用 Docker Desktop 的 `host.docker.internal` 后可运行。未把基础设施失败计作业务回归。
- 第一次基线联合执行中，补偿夹具过早断言完成。保留生产重试次数，缩短测试重试间隔并使用有界等待后，3 项基线失败均落在重载行为。
- 首次修复把初始可用量限制为等于总配额，导致原有“总配额 2、可用量 1”的预约用例失败。恢复原有初始化契约后全部通过，没有删改该原有断言来迎合实现。

本机最终使用 Java 21.0.11 / Maven Wrapper 3.9.16、MySQL 8.4.11、Redis 8.8.3-alpine。
Maven 在 `eclipse-temurin:21-jdk` 容器中运行，容器内存限额 2 GiB、JVM `-Xmx512m -XX:ActiveProcessorCount=4`，使用已有依赖缓存。
相关活动页面 **4 项组件测试通过**；全 Web ESLint、格式检查和浏览器脚本语法检查通过。Web 生产镜像完成 TypeScript 与 Vite 构建，Admin 生产镜像构建通过。
最终托管 CI 应以合并提交对应运行判定，不引用上一轮 CI 代替本轮结果。

## 桌面、手机与演示环境

在独立验收环境执行 [verify-admin-investigation.mjs](../../script/verify-admin-investigation.mjs)，桌面 1440×1000 与 Pixel 7 模拟各 9 项，共 **18 项通过**。
环境为 Windows x64、Node 22.20.0、Playwright 1.57.0、Chromium 143.0.7499.4；执行时间为 `2026-10-03T16:53:57.977Z` 至 `2026-10-03T16:54:58.425Z`。
检查包含三类独立分页、历史筛选、局部 503 后恢复、真实加载差异反馈、成功响应丢失后的截止时间，以及页面错误和横向溢出。
人工查看两种尺寸的活动告警截图，原因文字可换行、数值与排障入口可读。

浏览器检查访问真实认证与管理接口，但查询数据直接建立为隔离夹具；完整建单、超时和补偿链路由上述 Java 联合测试验证。
此次仅运行 Chromium / 手机尺寸模拟，不据此声称 Safari 或真实手机通过。
浏览器脚本清理了自己创建的 SQL 行及 Redis Key，保留追加式登录 / 装载审计，`cleanup=true`。

验收通过后，将同一 Admin 与 Web 镜像更新到现有主演示 `hotshop-task21-shop1002`，没有重置数据库。
镜像 ID、来源提交和启动时间记录于配套 JSON；主入口 HTTP 200，Admin 启动成功。
为缓解本机运行压力，旧的 `hotshop-modernize-0930`、`hotshop-task21-accept0916r01` 环境保持停止，数据卷保留；当前主演示与验收环境均已恢复运行。
未据此断言先前 Docker 故障一定由内存耗尽造成。

## 复现与剩余边界

在具备 Java 21、Docker 与项目依赖的环境执行：

```powershell
./mvnw -B -pl admin,portal -am '-Dtest=InventoryReconciliationJointContainerTest,FlashSaleReservationIntegrationTest,AdminFlashSaleActivityLoadServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
corepack pnpm@10.15.0 --dir web test src/pages/admin-activities-page.test.tsx
node script/verify-admin-investigation.mjs hotshop-task21-demo0001 http://127.0.0.1:18080
node script/check-docs.mjs
```

浏览器命令需替换为已经启动的独立演示项目及端口，前置条件见 [Web README](../../web/README.md#验证)。
本机原始红绿日志、筛选后的测试统计和构建日志保留于 `.local/verification/activity-reload-20261003/`，浏览器原始结果与截图保留于本次独立的 `admin-investigation-adm*` 目录。
公开 JSON 只保留统计、受测版本、镜像与验证边界，不上传包含系统属性的完整 Surefire XML。

`consistent` 说明两侧观察到的库存余额守恒，并非跨存储原子快照或订单投影完成证明。
MySQL 已回库、Redis 超时投影待执行时，两侧可以各自守恒；重载不能代替消费和投影。
超过 1,000 条 Redis 事件转交后台对账，MySQL 预约聚合仍随活动历史增长；后台扫描在持续写入下的推进与空间边界仍待专门验证。
已使用活动的配额修改、暂停 / 恢复、丢失事实恢复需要独立运营协议，仍列入[后续工作](../delivery/next-iteration.md)。
