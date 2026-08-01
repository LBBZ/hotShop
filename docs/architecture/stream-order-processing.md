# TASK-08 Redis Stream 订单转换、补偿与对账

## 1. 语义和边界

TASK-08 在现有单体多模块中由 `task` 模块消费 TASK-07 的
`RESERVATION_ACCEPTED` Stream 事件，把 Redis Reservation 转换为 MySQL 订单。它不发布
RabbitMQ Outbox、不处理支付，也不解决订单超时与支付终态竞争；这些属于 TASK-09/TASK-10。
实现不拆微服务、不使用数据库外键/级联删除、分布式锁或 Java 全局锁。

本链路的公开语义是：

- Redis Stream **至少一次投递**；
- MySQL 通过处理账本、唯一约束和确定性业务 ID 实现幂等业务效果；
- Redis 与 MySQL 通过 Pending 恢复和定时对账达到最终一致。

这不是 exactly-once。MySQL commit、Redis Lua 和 `XACK` 不在同一个原子事务中，进程可能在任意
两个边界之间退出，因此同一事件可以再次交付。系统保证重复交付不重复扣 MySQL 库存、不创建第二个
订单/订单项/Outbox，并补做尚未完成的 Redis 后处理。

补偿、修复、重放和对账没有 Agent API，也没有普通 HTTP 崩溃开关。故障注入仅通过测试 Bean
替换 `SeckillProcessingFailpoint`；生产默认使用 No-op 实现。

## 2. Stream 发现、消费者组与公平性

活动装载使用 `load-flash-sale-activity-v2.lua`。v1 文件保留不改；v2 在成功装载和同版本幂等装载时
原子执行：

```text
SADD hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams \
     hotshop:seckill:v1:{hotshop-seckill-v1}:activity:{activityId}:reservations
```

Registry Set、活动元数据、库存、Reservation 和 Stream 都带
`{hotshop-seckill-v1}` hash tag，Lua 的所有 Key 位于同一 Redis Cluster slot。消费者只读 Registry
Set，不使用 `KEYS` 发现活动。

每次发现新 Stream 后创建组：

```text
XGROUP CREATE <stream> hotshop-order-v1 0-0 MKSTREAM
```

`0-0` 使建组前已经存在的消息也可被消费。Consumer 名为
`<prefix>-<hostname>-<pid>-<random>`，每个实例唯一。新消息使用 `XREADGROUP ... >`，默认
`COUNT 20`、`BLOCK 2s`；无阻塞地轮询所有已发现 Stream 后，只有本轮没有消息时才阻塞一个
Stream。轮询起点每轮递增，配合每 Stream 的批量上限，避免某个高流量活动长期饿死其他活动。

默认参数见第 11 节，均可通过环境变量覆盖。

## 3. Pending 恢复和 ACK 边界

每轮先用 `XPENDING` 读取各 Stream 的 Pending 数、最老 idle 时间和 delivery count，再用：

```text
XAUTOCLAIM <stream> hotshop-order-v1 <consumer> <min-idle-ms> <cursor> COUNT <claim-batch>
```

认领超过 idle threshold 的记录。每个 Stream 独立保存 `XAUTOCLAIM` 返回的 next cursor；下一轮从
该 cursor 继续，Redis 返回 `0-0` 后重新开始完整扫描，而不是永远只认领第一页。账本中的
`next_attempt_at` 尚未到期时，消息仍留在 Pending，消费者不会绕过退避执行业务。

严格 ACK 顺序如下：

```text
订单成功：MySQL commit -> Redis ORDER_CREATED finalize -> XACK
补偿成功：MySQL COMPENSATING intent -> Redis compensation Lua
          -> MySQL COMPENSATED + audit + Outbox commit -> XACK
毒消息：  QUARANTINED/MANUAL_REVIEW + reconciliation issue commit -> XACK
```

瞬时基础设施故障、事务回滚、Redis finalize 失败或 Redis compensation 后 MySQL 最终提交失败都不
ACK。实现不使用 `XDEL`；原始 Stream 事件保留，`XACK` 只把消息从消费者组 PEL 中移除。异常后没有
无条件 ACK，也不会先 ACK 再处理。

## 4. 事件和 Redis 证据验证

消费者只接受 `schemaVersion=1`、`eventType=RESERVATION_ACCEPTED`，并严格验证：

- `eventId` 为 `evt_` 加 32 位小写十六进制，`reservationNo` 为 `rsv_` 加 32 位小写十六进制；
- `activityId`、`userId`、`productId`、`quantity` 为正数；
- `unitPrice` 为非负两位小数字符串且满足 `DECIMAL(19,2)`，`currency=CNY`；
- `status=RESERVED`，`activityVersion` 非负，`occurredAtMs` 为合理的正毫秒时间；
- `idempotencyKeyHash`、`requestFingerprint` 为 64 位小写十六进制；
- Stream key 中的 activity ID 与事件一致；
- Redis Reservation Hash 的 schema、Reservation/User/Activity/Product、数量、单价、币种、
  Activity Version、幂等 hash 和 fingerprint 与事件一致。

字段先按固定规则解析和规范化，不能由任意 Stream 字段拼 SQL、Redis Key 或日志。事件原始字段按
稳定字段顺序和长度计算 `payload_hash`；日志和 `last_error` 只记录限长分类码，不记录堆栈、Redis
地址、Token、Cookie 或原始 Idempotency-Key。

schema 损坏、Reservation Hash 缺失、不可验证的身份或事实冲突没有完整的安全恢复证据，会先写
`QUARANTINED` 或 `MANUAL_REVIEW` 账本与 OPEN 对账问题，再 ACK；绝不根据残缺事件恢复库存。

## 5. MySQL 订单事务

首次有效事件在一个 MySQL 事务中完成：

1. `INSERT ... ON DUPLICATE KEY` 抢占 `seckill_event_processing`，再按 `event_id` 或
   `(stream_key,stream_entry_id)` 加行锁；不依赖先查后插。
2. 插入 `sale_reservation`，或锁定同 `reservation_no` 的已有记录并逐项验证 immutable facts。
3. 按固定顺序条件扣减 `flash_sale_activity.available_stock`，再扣
   `catalog_product.stock`；两条 UPDATE 均要求库存 `>= quantity`。
4. 由 `SHA-256("hotshop/order/v1/" + reservationNo)` 计算稳定的
   `ord_<32 lowercase hex>`，创建一条 `sales_order` 和一条 `sales_order_item`。
5. 条件更新 Reservation：`RESERVED -> ORDER_CREATED` 并写入 `order_id`。
6. 写 `processed_event` 和一条确定性 `ORDER_CREATED` Outbox。
7. 将处理账本标为 `ORDER_CREATED`，然后 commit。

订单与行金额均为 `unitPrice * quantity`，使用 `BigDecimal`/`DECIMAL(19,2)`。订单初始状态是
`PENDING`；事件代表 Redis 已接受的库存承诺，因此不会因活动后来暂停或结束而拒绝。任一步失败则
整个事务回滚，包含两次库存扣减、Reservation、Order、Item、processed_event、Outbox 和处理账本。

ORDER_CREATED Outbox 的 event ID 由 Reservation 确定性计算，payload 包含 `schemaVersion=1`，
不含 Token、Cookie、原始 Idempotency-Key 或敏感身份字段。TASK-08 只写 Outbox，不发布到
RabbitMQ。

## 6. 幂等与冲突处理

幂等由下列相互补强的键实现：

| 层 | 键或约束 | 作用 |
| --- | --- | --- |
| 处理账本 | `UNIQUE(event_id)` | 同一事件只拥有一条持久状态 |
| 处理账本 | `UNIQUE(stream_key,stream_entry_id)` | 一个 Stream entry 不能换身份重放 |
| Reservation | `UNIQUE(reservation_no)` | 不同 event ID 可归并到同一 Reservation |
| Order | 确定性 `order_id` | 同一 Reservation 的重试不会生成随机新订单 |
| Order | Reservation 引用唯一 | 一个 Reservation 最多一个订单 |
| Item | `(order_id,product_id)` 唯一 | 重试不能增加第二条相同行项 |
| processed_event | `(consumer_name,event_id)` | 记录 `hotshop-order-v1` 的成功处理 |
| Outbox | 确定性 `event_id` 唯一 | 重试不能增加第二个同业务事件 |

具体语义：

- 同一 event ID、同一 payload：读取 `ORDER_CREATED` 账本和已提交订单，重做幂等 Redis
  finalize，随后 ACK；不会再次扣库存。
- 同一 event ID、不同 payload：记录 `EVENT_PAYLOAD_CONFLICT`，进入
  `QUARANTINED/MANUAL_REVIEW`，不产生新业务效果。
- 不同 event ID、同一 Reservation、immutable facts 相同：验证已有订单并归并到原
  `order_id`，新事件可登记为已处理。
- 不同 event ID、同一 Reservation、immutable facts 冲突：记录
  `RESERVATION_IMMUTABLE_CONFLICT` 并进入人工队列，不覆盖原事实。

## 7. Redis ORDER_CREATED finalize

`finalize-reservation-order-created-v1.lua` 只更新一个经过完整事实验证的 Reservation Hash：

```text
RESERVED -> ORDER_CREATED
```

它写入确定性 `orderId`、`orderCreatedAtMs`，不恢复库存、不删除 User 占位、不删除 Stream
事件。相同 order ID 重放返回幂等成功；不同 order ID、事实冲突或
`COMPENSATING/COMPENSATED` 均拒绝。调用顺序固定为 MySQL commit、finalize、`XACK`。

## 8. 重试、毒消息和补偿状态机

### 8.1 失败分类

| 分类 | 示例 | 处理 |
| --- | --- | --- |
| 瞬时基础设施失败 | MySQL/Redis 不可用、超时、死锁 | `RETRYING`，指数退避，留在 Pending；无论次数多少都不自动补偿 |
| 可安全补偿的确定性业务失败 | MySQL 活动或 Catalog 库存确定不足 | 有限重试后写 `COMPENSATING` intent |
| 无安全证据的损坏/冲突 | schema 错、身份冲突、Reservation 缺失或字段冲突 | `QUARANTINED/MANUAL_REVIEW` + 人工问题；不恢复库存 |

默认退避从 1 秒开始、乘数 2、最大 5 分钟；安全库存失败默认第 3 次进入补偿。任何时候发现已有有效
订单，补偿都被禁止，不因 delivery count 或 attempts 增大而放宽。

### 8.2 补偿状态机

```text
RESERVED --持久化意图--> COMPENSATING
COMPENSATING --Redis Lua + MySQL 最终提交--> COMPENSATED
```

`compensationId` 由
`SHA-256("hotshop/compensation/v1/" + reservationNo)` 确定性计算。补偿 Lua 同时校验
Reservation schema、activity ID、user ID、quantity、状态和 User 占位；在一个 Redis Lua 中：

1. 确认状态仅为合法的 `RESERVED/COMPENSATING`，User 占位仍指向当前 Reservation；
2. `INCRBY` 恰好恢复 quantity；
3. 删除当前 User 占位；
4. 写 `COMPENSATED`、`compensationId`、`reasonCode`、`compensatedAtMs` 和
   `stockRestored=1`。

相同 compensation ID 重放是幂等成功；不同 compensation ID、`ORDER_CREATED`、库存类型错误、
User 占位冲突均拒绝。Lua 中间错误不会留下“状态已补偿但库存未归还”的部分结果。Redis 成功后，
MySQL 事务把 Reservation/账本标为 `COMPENSATED`，写追加式审计和确定性
`RESERVATION_COMPENSATED` Outbox，最后 ACK。

## 9. 崩溃恢复矩阵

| 故障边界 | 持久事实 | 重启/认领后的动作 | 不变量 |
| --- | --- | --- | --- |
| 订单事务 commit 前 | MySQL 无部分订单效果，消息在 Pending | 重跑完整订单事务 | 库存未重复扣 |
| commit 后、Redis finalize 前 | 唯一订单和 `ORDER_CREATED` 账本已提交 | 验证已提交事实，补做 finalize、ACK | 不创建第二订单 |
| finalize 后、ACK 前 | MySQL 与 Redis 均为 ORDER_CREATED，消息仍 Pending | finalize 幂等检查、ACK | 不重复扣库存 |
| 写 COMPENSATING intent 前 | 仍为 RESERVED/RETRYING | 按失败分类继续重试 | 不提前恢复库存 |
| intent 后、补偿 Lua 前 | MySQL 有确定性 compensation intent | 重放同 compensation ID Lua | 库存最多恢复一次 |
| 补偿 Lua 后、MySQL 最终提交前 | Redis 已 COMPENSATED，MySQL 仍 COMPENSATING | Lua 幂等重放，再提交 MySQL 审计/Outbox | User 占位只释放一次 |
| MySQL COMPENSATED 后、ACK 前 | 两端补偿终态已提交，消息仍 Pending | 验证终态、ACK | 不再次 `INCRBY` |
| MySQL 临时不可用 | 无法提交或更新账本 | 不 ACK、不补偿；依靠 Pending 恢复 | Redis 承诺保留 |
| Redis 临时不可用且订单已提交 | MySQL ORDER_CREATED | 不 ACK；恢复后只补 finalize | 不创建第二订单、不补偿 |

## 10. 对账、人工队列与修复白名单

`SeckillReconciliationService` 按 Registry Set 中的 Stream 排序扫描，默认每 5 分钟最多检查 100
条事件。`seckill_reconciliation_checkpoint` 为每个活动 Stream 保存 entry ID cursor；扫描到末尾后
从 `0-0` 开始下一轮，允许批量、可断点续跑。
Registry 另有一个全局 Stream cursor，每轮从上次活动的下一个活动开始，避免大活动长期占满批次。
对账同时按 `sale_reservation.reservation_id` 保存 MySQL 反向 cursor，用于发现缺少 Stream/处理账本
证据的 Reservation，以及有 Reservation 引用却没有对应 Reservation 行的孤儿订单。

至少检查：

- Stream 事件与 Redis Reservation Hash 的 immutable facts 是否一致；
- 有效 Reservation 的 User 占位是否指回自己，COMPENSATED 后占位是否释放；
- Redis 当前库存为非负，补偿证据包含同一个 compensation ID 和 `stockRestored=1`；
- MySQL Reservation 与 Order 是否一一对应，ORDER_CREATED 是否恰有一个订单；
- MySQL ORDER_CREATED 是否已同步到 Redis；
- Activity/Catalog MySQL 库存是否只按成功订单数量扣一次；
- terminal 处理账本是否仍滞留 Pending；
- 同一 Reservation 是否出现多订单、payload/身份冲突。

库存守恒公式：

```text
Redis:
initialAvailableStock - currentRedisStock
  = SUM(quantity of distinct Reservation in RESERVED|ORDER_CREATED|COMPENSATING)

MySQL activity:
currentActivityStock
  = initialAvailableStock - SUM(quantity of Reservation with exactly one ORDER_CREATED Order)

MySQL catalog:
currentCatalogStock
  = initialCatalogStock - SUM(quantity of all ORDER_CREATED Reservations for the same product)
```

`COMPENSATED` 不计入 Redis 有效 Reservation 数量，因为其 quantity 已由 Lua 恢复；同一
Reservation 的重复 Stream event 只计一次。

默认 `reconciliation-dry-run=true` 且 `auto-repair=false`。dry-run 允许写
`seckill_reconciliation_issue`、checkpoint 和指标，但不得修改 Redis 库存、User 占位、
Reservation、Order 或 MySQL 活动/Catalog 库存。问题以稳定 `issue_key` 合并，状态为
`OPEN/RESOLVED/IGNORED`，证据是版本化、脱敏、限量 JSON 摘要。

只有同时配置 `dry-run=false` 和 `auto-repair=true` 才进入修复模式，白名单仅包含：

- 根据已提交且验证一致的 MySQL Order 补做 Redis ORDER_CREATED finalize；
- 根据已持久化 COMPENSATING intent 重放同 compensation ID 的补偿；
- 对账本已处于安全终态的 Pending entry 执行 `XACK`。

禁止按库存差值直接改库存、凭空创建 Order、覆盖冲突 immutable facts。任何证据模糊的情况进入人工
队列。人工处置前应同时核对 Stream 原文、Redis Hash/User 占位、处理账本、Reservation/Order/Outbox
及审计记录；不得只看一侧状态。

既有 `OrderTimeoutJob` 只扫描 `sales_order.reservation_id IS NULL` 的普通订单；旧 Rabbit 超时消息
的消费端也只能调用带 `status='PENDING' AND reservation_id IS NULL` 条件更新的 legacy 取消方法。
因此延迟或误投的旧超时消息不能取消 TASK-08 秒杀订单；支付与订单超时终态竞争留给
TASK-09/TASK-10。

## 11. 配置与指标

`task` Compose 服务接收以下环境变量：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `HOTSHOP_SECKILL_ORDER_CONSUMER_ENABLED` | `true` | 开关消费者 |
| `HOTSHOP_SECKILL_ORDER_GROUP` | `hotshop-order-v1` | 消费者组 |
| `HOTSHOP_SECKILL_ORDER_CONSUMER_PREFIX` | `order` | 实例唯一 consumer 名前缀 |
| `HOTSHOP_SECKILL_ORDER_READ_BATCH` | `20` | 新消息每 Stream 批量 |
| `HOTSHOP_SECKILL_ORDER_READ_BLOCK` | `2s` | 空闲阻塞读取 |
| `HOTSHOP_SECKILL_ORDER_POLL_DELAY` | `250ms` | 轮询间隔 |
| `HOTSHOP_SECKILL_ORDER_DISCOVERY_INTERVAL` | `10s` | Registry 刷新 |
| `HOTSHOP_SECKILL_ORDER_CLAIM_IDLE` | `30s` | Pending 可认领 idle |
| `HOTSHOP_SECKILL_ORDER_CLAIM_BATCH` | `20` | 每页认领数量 |
| `HOTSHOP_SECKILL_ORDER_RETRY_INITIAL_BACKOFF` | `1s` | 初始退避 |
| `HOTSHOP_SECKILL_ORDER_RETRY_MAX_BACKOFF` | `5m` | 最大退避 |
| `HOTSHOP_SECKILL_ORDER_RETRY_MULTIPLIER` | `2.0` | 退避乘数 |
| `HOTSHOP_SECKILL_ORDER_DETERMINISTIC_FAILURE_ATTEMPTS` | `3` | 安全库存失败补偿阈值 |
| `HOTSHOP_SECKILL_ORDER_TIMEOUT` | `15m` | Reservation 落库过期时间 |
| `HOTSHOP_SECKILL_PAYMENT_TIMEOUT` | `15m` | 新订单支付期限字段 |
| `HOTSHOP_SECKILL_RECONCILIATION_INTERVAL` | `5m` | 对账周期 |
| `HOTSHOP_SECKILL_RECONCILIATION_BATCH` | `100` | 单轮事件上限 |
| `HOTSHOP_SECKILL_RECONCILIATION_DRY_RUN` | `true` | 默认只报告 |
| `HOTSHOP_SECKILL_RECONCILIATION_AUTO_REPAIR` | `false` | 修复白名单总开关 |

Micrometer 指标不使用 event ID、activity ID、reservationNo、stream key 等高基数标签：

```text
hotshop.seckill.order.consumed
hotshop.seckill.order.processed
hotshop.seckill.order.duplicate
hotshop.seckill.order.retried
hotshop.seckill.order.claimed
hotshop.seckill.order.quarantined
hotshop.seckill.order.manual_review
hotshop.seckill.order.compensated
hotshop.seckill.order.processing_failures
hotshop.seckill.order.pending
hotshop.seckill.order.pending_oldest_idle_ms
hotshop.seckill.order.conversion_latency
hotshop.seckill.reconciliation.findings
```

## 12. 运行与调查命令

以下示例假定活动 ID 为 7001；Compose 已为 Redis 容器设置 `REDISCLI_AUTH`，避免密码出现在命令
参数中。

```powershell
$stream = 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations'
$registry = 'hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams'

docker compose --env-file .env.example exec -T redis-seckill redis-cli SMEMBERS $registry
docker compose --env-file .env.example exec -T redis-seckill redis-cli XINFO GROUPS $stream
docker compose --env-file .env.example exec -T redis-seckill redis-cli XPENDING $stream hotshop-order-v1
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  XPENDING $stream hotshop-order-v1 - + 100
docker compose --env-file .env.example exec -T redis-seckill redis-cli XRANGE $stream - + COUNT 100
```

处理账本、人工问题、Outbox 和 dry-run 结果：

```powershell
docker compose --env-file .env.example exec -T mysql sh -c `
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --database="$MYSQL_DATABASE" --table -e "
   SELECT event_id,stream_entry_id,reservation_no,status,attempts,next_attempt_at,
          order_id,compensation_id,reason_code,updated_at
     FROM seckill_event_processing
    ORDER BY updated_at DESC LIMIT 100;
   SELECT issue_id,issue_type,severity,status,activity_id,reservation_no,
          occurrences,last_seen_at,evidence_version
     FROM seckill_reconciliation_issue
    WHERE status = ''OPEN''
    ORDER BY severity DESC,last_seen_at DESC LIMIT 100;
   SELECT checkpoint_name,cursor_value,version,updated_at
     FROM seckill_reconciliation_checkpoint
    ORDER BY checkpoint_name;
   SELECT event_id,aggregate_type,aggregate_id,event_type,status,publish_attempts,created_at
     FROM outbox_event
    WHERE event_type IN (''ORDER_CREATED'',''RESERVATION_COMPENSATED'')
    ORDER BY outbox_id DESC LIMIT 100;"'
```

终态仍在 Pending 的候选可先用 `XPENDING ... - + 100` 取得 entry ID，再执行只读 SQL：

```sql
SELECT event_id, stream_key, stream_entry_id, status, order_id, compensation_id
FROM seckill_event_processing
WHERE stream_key = :stream_key
  AND stream_entry_id = :stream_entry_id
  AND status IN ('ORDER_CREATED','COMPENSATED','QUARANTINED','MANUAL_REVIEW');
```

不要手工 `XACK`，除非已完成上述跨存储核验并遵循变更审批；不要使用 `XDEL` 代替 ACK。调查时不要把
完整 payload、凭据或连接地址复制到工单，只记录 event ID、Reservation No、reason code 和脱敏
证据摘要。

## 13. 用户状态查询与已知限制

User 可调用：

```text
GET /api/v1/flash-sales/{activityId}/reservations/{reservationNo}
authority: ROLE_USER
```

服务优先返回 MySQL 最终事实，尚未落库时读取 Redis Reservation。状态包括 `RESERVED`、
`ORDER_CREATED`、`COMPENSATING`、`COMPENSATED`；ORDER_CREATED 可返回 `orderId`。User ID 只来自
Principal；未知 Reservation 与他人 Reservation 统一返回 404，防止枚举。Agent boundary 不提供此
后台处理、补偿或修复能力。

已知限制：

- TASK-08 的 Outbox 只持久化，不发布 RabbitMQ；
- 支付、订单超时终态竞争和支付后状态推进未在本任务实现；
- 原始 Stream 不自动截断；制定归档/保留策略前不得 `XDEL`；
- 所有秒杀 v1 Key 使用一个全局 hash slot，后续分片需要升级 Key 与 Lua 协议；
- dry-run 仍会写对账 issue、checkpoint 和指标，这些是观察性元数据，不是业务修复；
- 自动修复默认关闭，且不包含库存差值修复、创建订单或覆盖冲突事实。
