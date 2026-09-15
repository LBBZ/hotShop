# 独立复核：第一轮风险报告

- 基线：`a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`（master）。
- 日期：2026-09-15。
- 状态：发现阻断最终交付的问题；不是全项目验收完成，也不是正式安全扫描报告。
- 本轮只读业务代码，新增本报告及诊断复现材料；未修改业务实现、合并、提交或推送。
- 结论依据是代码和复现，不以模型名称判断此前实现的质量。

## 1. 已确认问题

### IR-01 / P1：后台编辑商品会覆盖已提交的库存扣减

代码链：

- `web/src/pages/admin-products-page.tsx:43`：打开编辑器时缓存商品库存；只改名称仍提交整个 draft。
- `common/src/main/java/com/real/common/api/dto/AdminProductMutationRequest.java:20`：请求包含绝对库存，不包含读取版本。
- `admin/src/main/java/com/real/admin/service/AdminProductAuditService.java:84`：提交时加行锁，然后覆盖商品。
- `domain/src/main/java/com/real/domain/mapper/ProductMapper.xml:18`：`stock = #{stock}`，没有版本条件。
- 同文件 `:23`：普通下单扣减库存，但也没有递增 version。

时序：管理员打开库存 100 的表单 → 用户下单成功，库存 99 → 管理员只改名称并保存 → 库存回到 100。行锁只串行化提交，不能识别旧表单。多出来的可售库存可以继续被下单，破坏库存守恒。

验证：在隔离 MySQL 8.0.46 上执行与 Mapper 对应的 UPDATE 和后台 SELECT FOR UPDATE，实际输出 `99 → 100`，version 仍为 0。诊断使用最小表，并未通过完整 HTTP/前端复现，证据边界见附件。

修复验收条件：

- 普通商品信息编辑不能隐式调整库存；显式库存调整必须有独立语义、并发控制和审计。
- 如选择乐观锁，所有相关库存变更必须参与版本递增，不能只给后台 PUT 增加版本字段。
- 增加真实 MySQL 测试：旧表单 + 普通订单、旧表单 + 秒杀扣减、旧表单 + 超时回补；都不能静默覆盖库存变化。
- 增加前端/HTTP 测试：只改名称，不改变当前库存；冲突有可理解的提示。

### IR-02 / P1：取消半开探测会使 Agent 熔断器无法自行恢复

代码链：

- `agent/src/hotshop_agent/reliability.py:61`：半开探测设置 `half_open_in_flight = True`。
- `:132`：ReliableModel 获取探测资格。
- `:142` 起：仅处理 timeout、permanent、temporary 错误；没有在取消/关闭生成器时释放探测资格。
- `agent/src/hotshop_agent/service.py:216` 附近：取消接口确实会执行 task.cancel()。
- `agent/src/hotshop_agent/container.py`：一个 ReliableModel / CircuitBreaker 被该进程中的请求共用。

已直接运行当前源码复现：先进入 HALF_OPEN，阻塞 provider，然后取消请求。并发计数恢复到 0，但探测标志保持 True。之后连续三次调用均被拒绝为 `model circuit is probing`。在没有其他在途调用改变熔断状态的情况下，继续等待恢复时间也不会清除此标志，需要重建实例或重启。

修复验收条件：

- 明确探测资格的所有权和释放规则；取消、生成器关闭、意外异常均不能遗留资格。
- 不要通过不分所有者的全局 finally 清零，避免旧请求错误释放新探测。
- 增加 HALF_OPEN + 用户取消、流关闭、超时、成功、失败测试；验证随后确实能再次探测。
- 增加并发测试：旧请求完成不能错误覆盖新的熔断周期；业务并发计数仍正确释放。

### IR-03 / P2：普通购买会触发秒杀库存守恒误报

代码链：

- `domain/src/main/java/com/real/domain/service/advance/OrderStateService.java:77` 附近：普通订单直接减少同一 catalog_product 库存。
- `domain/src/main/java/com/real/domain/service/seckill/FlashSaleActivityLoader.java:96`：装载活动时保存当时的商品库存快照。
- `task/src/main/java/com/real/task/seckill/SeckillReconciliationService.java:637`：商品消耗量只统计 sale_reservation 的 ORDER_CREATED 记录。
- `:652`：比较 `catalogStock == initialCatalogStock - productSuccessful`，不含普通订单或合法库存调整。

复现条件：装载活动时商品库存 100；之后仅发生一笔普通购买，当前库存 99，秒杀成功量 0。当前公式比较 99 == 100，记录 `MYSQL_STOCK_CONSERVATION_VIOLATION / CRITICAL`。隔离 MySQL 算式验证结果为 false；全链路条件由上述源码确认，尚未通过 Spring reconciliation 集成测试触发 issue 行。

影响：合法业务进入严重异常队列，使对账报告不可信。本轮没有证据表明此误报会自动错误回补库存，不能扩大为已确认的自动补偿事故。

修复验收条件：

- 明确定义商品库存与活动配额各自的守恒边界、快照时间和流水范围。
- 不可简单把“所有历史普通订单”加进当前快照公式，也不可关闭守恒检查来消除告警。
- 覆盖同商品普通购买、取消回补、多个活动在不同时间装载、合法后台调整。
- 合法场景零误报；真正篡改库存仍能被发现。

### IR-04 / P2：对账批量上限不限制实际扫描规模

- `task/src/main/java/com/real/task/seckill/SeckillReconciliationService.java:291`：每次先 XRANGE 完整 Stream，无 COUNT。
- `:303` 附近：随后才执行带 remainingBatch 的读取。
- `:352`、`:565`：守恒检查继续遍历完整历史集合，并为每个 reservation 发起 HGETALL。

因此 reconciliation-batch=100 只限制部分逐事件检查，不限制完整历史加载及 Redis 往返。历史 N 条时仍有 O(N) 读取、内存和串行往返成本。此为静态确认；本轮没有运行百万条压测，不能声称已测得 OOM 或某个耗时。

修复验收条件：

- 对账的每次调用有明确记录数、字节量或时间预算；全量守恒核算也需要可续跑边界。
- 并发业务写入时不能使用不一致的分页快照制造新误报。
- 用超过批量上限的历史数据验证单次预算和最终覆盖；检查实际 Redis 命令，不能只断言报告 checkedEntries。

## 2. 本轮执行证据

Agent 使用现有测试镜像作为 Python 依赖环境，但只读挂载并导入当前工作树源码；禁用网络，未调用付费模型。

```powershell
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop,target=/review,readonly -w /review/agent -e PYTHONPATH=/review/agent/src -e PYTHONDONTWRITEBYTECODE=1 --entrypoint python hotshop-agent:task20-test -m pytest -o addopts= -p no:cacheprovider -p pytest_asyncio.plugin -m 'not qdrant' --tb=short -q
```

结果：**250 passed, 6 skipped, 8 deselected in 28.43s**。6 项容器运行时测试因未配置镜像参数而跳过；8 项真实 Qdrant 测试主动排除。另一次 security / registry / model provider contracts / embeddings 定向运行：86 passed in 9.62s。

首轮定向执行未显式加载 pytest_asyncio，异步测试未执行而失败；加入 `-p pytest_asyncio.plugin` 后通过。这是诊断命令配置问题，不计为业务缺陷。

复现附件：

- `docs/quality/review-2026-09-15/probe_circuit_cancel.py`：直接导入当前源码，无业务修改。
- `docs/quality/review-2026-09-15/probe_inventory.sql`：最小 MySQL 数据及对应 SQL 时序，不是完整应用集成测试。

临时 MySQL 容器使用内存数据目录，已停止并自动删除；没有挂载或清理用户数据卷。没有执行 Maven clean，也没有删除既有压测证据。

## 3. 已查看但不能扩大结论的部分

- 秒杀建单路径包含事务内库存扣减、订单与 Outbox 写入；超时路径包含订单锁、条件终态及预占 CANCELED / 库存回补。没有据此认定完整竞态矩阵通过。
- Outbox 发布代码等待 confirm，并检查 returned message；这不是本轮重新完成 RabbitMQ 故障验证的证据。
- Agent 工具调用前重验凭证、owner、scope，工具路径固定；模型工厂保留 DeepSeek/Qwen，Embedding 单独构建。定向测试通过不等同于完整权限安全审计。
- TASK-20 当前 target-5k 方案包含逐请求登录；延迟指标统计 reservation HTTP 部分，吞吐分母为到达窗口且计入尾部完成。已有文档声明这些边界，本轮不把已声明口径当作隐藏造假，但它不能证明纯秒杀入口的稳定 5000 RPS。

## 4. 后续工作与分支安排

本轮不推翻 TASK-00 至 TASK-20 的所有已验收结果；这些新增跨模块缺陷说明最终交付应继续暂停。

建议下一批实现任务（本报告不自动启动开发）：

| 任务 | 分支（不使用 codex/） | 范围 | 依赖 |
| --- | --- | --- | --- |
| REVIEW-FIX-01 | review-fix-01-inventory-edit | IR-01，后台编辑/库存调整并发语义及跨层回归 | 无 |
| REVIEW-FIX-02 | review-fix-02-agent-recovery | IR-02，探测生命周期与取消恢复测试 | 无，可与 01 并行 |
| REVIEW-FIX-03 | review-fix-03-reconciliation | IR-03 + IR-04，守恒边界及有界对账 | 等 01 库存调整语义确定；两个问题同属一个服务，不建议拆并行改 |

每个实现任务必须先写能在基线上失败的回归测试，再修复；提交时提供基线失败/修复通过的证据、精确命令和剩余风险。不要只追加文档或放宽检查。验收者核对分支 diff 并复跑关键场景后再合并。

完整独立复核仍待覆盖：Java 完整事务/消息故障矩阵、真实 Qdrant 与容器权限验证、全新 Compose 用户/后台/Agent 浏览器闭环、长时稳态压测与端到端延迟、恢复演练。以上本轮未重新执行，不能沿用历史绿灯冒充本轮结果。
