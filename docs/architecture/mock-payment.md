# Mock Payment（仅本地演示）

Mock Payment 是 HotShop 单体中的本地演示能力，不是真实支付，不连接任何公网支付服务，也不会转移资金。默认关闭；仅当 `HOTSHOP_MOCK_PAYMENT_ENABLED=true` 且外部注入至少 32 UTF-8 字节的 `HOTSHOP_MOCK_PAYMENT_SECRET` 时启用。

## HTTP 与事实边界

User 只能以 `ROLE_USER` 创建、查询自己订单的支付单并安排 mock action。金额、币种、User、Order ID 和支付截止时间都从锁定的 MySQL 订单读取；请求没有金额字段。`/provider-callbacks/v1/mock-payment` 是唯一匿名 Provider 路径，它不读取 JWT，也不能借助 Bearer Token 绕过 HMAC。该路径只出现在独立的 `mock-provider-callback` OpenAPI，不进入 Public、User、Admin 或 Agent 客户端。

## HMAC canonicalization 与防重放

签名算法是 HMAC-SHA256，十六进制小写输出。输入字节严格为：

```text
ASCII(timestamp) || 0x0a || ASCII(nonce) || 0x0a || raw HTTP request body bytes
```

Portal 先限制请求体为最多 4096 字节，再同时拒绝过旧和过度超前的 epoch-second timestamp，最后使用常量时间比较验签。JSON 必须恰好包含白名单中的七个字段；UUID、业务号、outcome、两位小数字符串、CNY 和 ISO-8601 occurredAt 都有长度和格式上限。

`callbackId` 标识一次 Provider 业务通知，重复通知保持不变；`nonce` 标识一次 HTTP 尝试，每次投递都不同。MySQL 只保存 nonce 的 SHA-256 哈希且有唯一键。相同 callbackId 与相同原始 payload hash 返回幂等成功；相同 callbackId 的不同 payload、nonce 复用和数据库事实冲突都拒绝并写失败审计。签名、原始 nonce、Secret 和完整请求体不进入日志、审计或响应。

## 持久化延迟与投递

mock action 与业务事务一起插入 `MOCK_PAYMENT_CALLBACK_REQUESTED` Outbox，并把延迟写进 `outbox_event.available_at`。Outbox 租约发布器重启后会重新领取 NEW 或过期 PUBLISHING 记录；RabbitMQ 暂时不可用时仍保留 MySQL 事实。

发布后的 durable 消息进入专用 callback queue。Task 在投递时才生成 timestamp、nonce 和 HMAC，并通过 Docker 内网调用 Portal。HTTP 2xx 后 ACK；确定性 4xx reject 到 DLQ；连接失败和 5xx 进入有 TTL 的 durable retry queue，达到上限后进入 DLQ。Broker confirm 与 consumer ACK 之间仍可能崩溃，因此系统只能保证 at-least-once 投递；callback ledger、nonce 表和条件状态更新把重复投递收敛为幂等业务效果。

重试消息携带从 1 开始递增的 `x-hotshop-delivery-attempt`，并设置为 persistent。Task 只有在 retry publish 获得 correlated broker ACK 且没有 mandatory return 后才 ACK 原消息；NACK、return、confirm timeout 或 exchange 不存在都会保留原 delivery。持续 503 恰好调用配置的 `max-delivery-attempts` 次，最后一次 reject 到 `hotshop.mock-payment.callback.dead.v1`，主队列和 retry queue 不再产生后续消息。

## 统一锁顺序与终态矩阵

所有能决定订单终态的事务先锁 `sales_order`，再锁同订单的 `payment_order`。秒杀超时继续按 `sale_reservation`、`flash_sale_activity`、`catalog_product` 的顺序加锁。创建支付也先锁 Order，再读取/创建 Payment。不要新增反向锁序。

| 先获得订单锁的一方 | Order | Payment | 库存 | 结果事件 |
| --- | --- | --- | --- | --- |
| 支付成功 | `PENDING -> PAID` | `PENDING/FAILED -> SUCCEEDED` | 不补偿 | `PAYMENT_SUCCEEDED` |
| 超时 | `PENDING -> CANCELED` | `PENDING -> CLOSED` | 恰好补偿一次 | `ORDER_CANCELED`，秒杀另有 `SECKILL_PAYMENT_EXPIRED` |
| 取消后的成功 | 保持 `CANCELED` | `PENDING/FAILED/CLOSED -> LATE_SUCCEEDED` | 不撤销已提交补偿 | `PAYMENT_LATE_SUCCEEDED`，进入人工处理 |
| 失败回调 | 保持 `PENDING` | `PENDING -> FAILED` | 不补偿 | `PAYMENT_FAILED` |

普通订单仅恢复 `catalog_product`。秒杀订单在同一 MySQL 事务恢复 catalog stock 和 activity available stock，并把 Reservation 置为 `CANCELED`；随后可靠的 `SECKILL_PAYMENT_EXPIRED` 事件用幂等 Lua 修复 Redis stock、Reservation 投影和 User slot，禁止 MySQL/Redis 裸双写。

库存恢复还会在同一事务追加 `INVENTORY_COMPENSATED` 审计。actor 为 SYSTEM、source 为 TASK、result 为 SUCCESS；资源是普通订单或秒杀 Reservation，摘要只包含 ORDINARY/SECKILL、PAYMENT_TIMEOUT 和必要的脱敏资源事实。订单取消、支付关闭、MySQL 库存、Reservation、Outbox、Inbox 与该审计任一失败都会整体回滚；重复 timeout event 由 Inbox 唯一键阻止再次审计或补偿。

`SECKILL_PAYMENT_EXPIRED` 投影消费者严格校验 envelope/payload 的精确字段集合、版本、UUID、聚合标识以及订单、Reservation、User、Activity、Product、quantity 和 reason。成功或 Lua `IDEMPOTENT` 后 ACK；Schema 或 Redis 事实冲突 reject 到 `hotshop.seckill.payment-expired.dead.v1`；Redis 连接故障进入 persistent TTL retry queue。retry publish 必须先获得 broker confirm，达到配置上限后只产生一条 DLQ 消息。

## 审计与运维

合法 callback 的 ledger、业务状态、结果 Outbox 和成功审计使用同一事务；审计失败会回滚高风险成功写入。验签、时间、重放和事实冲突的失败审计使用新事务。审计摘要仅包含 provider、paymentNo、outcome、result/category 和 previous/new status。

`PaymentTerminalRaceContainerTest` 不复制支付 UPDATE：支付线程用真实 `MockPaymentProvider` 对原始 JSON 签名并调用生产 `MockPaymentCallbackService.accept`，超时线程调用带真实事务拦截器的生产 `OrderTimeoutService.process`。支付先赢、超时先赢和 20 轮并发均同时断言 nonce、callback ledger、结果 Outbox、ORDER_CANCELED、processed_event、支付审计、库存审计及重复 callback 的幂等效果。

DLQ：`hotshop.mock-payment.callback.dead.v1` 和 `hotshop.seckill.payment-expired.dead.v1`。排障时只能查看事件 ID、状态、失败分类和脱敏业务号，不得导出请求体、签名或 nonce。修复 Portal/Broker/Redis 后通过现有受审计的 Outbox replay 恢复 FAILED Outbox；DLQ 消息需先确认确定性错误已经修复再人工重投。
