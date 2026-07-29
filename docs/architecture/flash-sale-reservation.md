# TASK-07 Redis Lua 秒杀预约设计

## 1. 责任边界

TASK-07 只接收 Reservation。`POST /api/v1/flash-sales/{activityId}/reservations` 在
`redis-seckill` 的一个 Lua 脚本中完成校验、库存预扣、Reservation/User/幂等事实写入和 `XADD`，
然后返回 `202 Accepted`。热路径不写 MySQL、不创建 Order、不调用 RabbitMQ、模型或 Agent，也没有
Java 全局锁或分布式锁。

MySQL 仍是最终交易事实来源。Redis Stream 中的 `RESERVATION_ACCEPTED` 是 TASK-08 的输入；
TASK-08 才负责消费、Pending List/重试/认领、创建 `sale_reservation` 与 `sales_order`、补偿和对账。
现有 `POST /api/v1/orders` 是兼容保留的同步普通 Order 接口，不是最终秒杀入口。

## 2. 固定双 Redis 连接

应用启动时只创建以下具名 Lettuce 工厂和字符串客户端，两个工厂都固定 `database=0`：

| 实例 | Bean | 数据 |
| --- | --- | --- |
| `redis-cache` | Primary `cacheRedisConnectionFactory` / `cacheStringRedisTemplate` | 认证 denylist、限流、Agent replay、普通缓存；可淘汰、可重建 |
| `redis-seckill` | `seckillRedisConnectionFactory` / `seckillStringRedisTemplate` | 活动、库存、Reservation、User 占位、幂等结果、Stream；`noeviction`、AOF+RDB |

安全组件按类型注入时得到 Primary cache 客户端；秒杀服务必须用
`@Qualifier("seckillStringRedisTemplate")`。不再存在 `RedisTemplateGenerator`，请求期间不会创建
`LettuceConnectionFactory`。旧 `RedisService` 仅为未归属本任务的 task 调用保留兼容签名，参数
`dbIndex` 不再选择数据库，所有操作固定进入 `redis-cache` DB 0。

## 3. Key、hash tag 与生命周期

公共前缀是 `hotshop:seckill:v1:{hotshop-seckill-v1}`。所有 Lua Key 使用同一个 hash tag，因而在
Redis Cluster 中计算到同一个 slot。使用全局 tag 是为了让 User 级 Idempotency-Key 能同时约束不同
activityId；代价是 TASK-07 的秒杀写流量集中在一个 slot，后续若要分片，必须先重新设计跨活动幂等
协议并升级 Key/脚本版本，不能直接改 v1。

Key 不包含 Access Token、Cookie、Email、Username 或原始 Idempotency-Key。Idempotency-Key 先做
SHA-256；User 使用稳定数值 ID。

| 事实 | Key 模板 | 类型 | TTL / 生命周期 |
| --- | --- | --- | --- |
| 活动元数据 | `...:activity:{activityId}:meta` | Hash | `endsAt + 7d`，普通装载刷新 |
| 可售库存 | `...:activity:{activityId}:stock` | String integer | `endsAt + 7d` |
| User 有效占位 | `...:activity:{activityId}:user:{userId}:reservation` | String reservationNo | 接受时计算 `endsAt + 7d` |
| 幂等结果 | `...:idempotency:user:{userId}:{sha256(key)}` | Hash | 24h |
| Reservation | `...:activity:{activityId}:reservation:{reservationNo}` | Hash | 接受时计算 `endsAt + 7d` |
| 活动 Stream | `...:activity:{activityId}:reservations` | Stream | 无 TTL、无 `MAXLEN`；TASK-08 消费/归档策略落地前不截断 |
| 装载 staging | `...:activity:{activityId}:load:{loadId}:{meta\|stock}` | 临时 Hash/String | 同一 Lua 内 rename 或删除，不跨请求保留 |

请求热路径只按计算好的 Key 做 O(1) 访问，不使用 `KEYS` 或 `SCAN`。对账的 `XRANGE` 只出现在显式
管理装载/校验路径，不属于秒杀请求热路径。

## 4. MySQL → redis-seckill 装载

Administrator 调用：

```text
POST /admin/api/v1/flash-sales/{activityId}/load
authority: PERM_ADMIN_FLASH_SALE_LOAD
```

服务读取 `flash_sale_activity` 并 LEFT JOIN `catalog_product`，检查：

- Catalog Product 存在、未删除且 `ACTIVE`，引用 ID 一致；
- 活动价与 Catalog 价格非负、两位小数，活动价不高于 Catalog 价格；
- 总库存大于 0、可售库存处于 `[0,total]`、总库存不超过 Catalog stock；
- per-User limit 大于 0且不超过总库存；
- `endsAt > startsAt`，status 属于数据库允许集合，version 非负。

装载 Lua 比较 `databaseVersion`：

- Redis 无事实或数据库版本更新且 Stream 为空：用 staging Key 写全量事实后替换；
- 同版本且所有事实字段一致：返回 `IDEMPOTENT`，绝不重置已扣库存；
- 旧版本：`STALE_VERSION`；
- 已有 Stream 事件时尝试装载更新版本：`RESERVATIONS_EXIST`，禁止静默重置库存或清 User 占位；
- 类型或同版本事实不一致：`INTERNAL_STATE_INVALID`。

成功响应同时返回 MySQL available stock、Redis stock、Stream 事件数、Reservation Key 数、累计预约
quantity 和 `consistent`。管理调用由 Administrator 身份强制授权，并把结果/失败原因、request ID、
trace ID 和对账摘要写入只追加 `audit_log`。

## 5. Reservation Lua v1

### 输入

六个 Key：metadata、stock、User 占位、User 全局幂等、Reservation、activity Stream。参数只包含稳定
ID、quantity、SHA-256 request fingerprint、服务端生成的 reservation/event 编号、request ID、TTL
和 Idempotency-Key hash。User ID 只来自已验证 Principal。

fingerprint 是 SHA-256(`"v1\n" + activityId + "\n" + quantity`)。同一 User、同一 Key、相同
fingerprint 重放原 reservationNo/status/requestId，并设置 `Idempotency-Replayed: true`；不同
activityId 或 quantity 返回 `IDEMPOTENCY_CONFLICT`。幂等结果保留 24 小时。

### 执行与失败安全

脚本先完整验证六个 Key 类型、metadata 字段、quantity、幂等绑定、Redis `TIME`、活动 status、User
占位和库存。写入顺序为：

1. Reservation Hash（带 TTL）；
2. User 占位（`SET NX EX`）；
3. 幂等 Hash（带 TTL）；
4. 不带 `MAXLEN` 的 `XADD`；
5. 最后 `DECRBY` 库存。

所有可能失败的写使用 `redis.pcall`。步骤 1–4 任一失败会删除本脚本已创建的 Key；`XADD` 后库存写
失败会先 `XDEL` 该事件再删除三个事实 Key。库存扣减是最后一个业务写，成功后没有第二个可能失败的
业务写，因此不会出现“库存已扣但没有 Stream 事件”。错误类型、`noeviction` OOM 或其他受控写拒绝
返回 `INTERNAL_STATE_INVALID`，HTTP 映射为脱敏 503；Redis 连接不可用映射为
`SECKILL_SERVICE_UNAVAILABLE` 503，绝不降级为 MySQL 同步下单。

### 返回码

| Lua code | HTTP | Problem code / 语义 |
| --- | --- | --- |
| `ACCEPTED` | 202 | 首次接受 |
| `IDEMPOTENT_REPLAY` | 202 | 原结果重放，响应头 `Idempotency-Replayed: true` |
| `IDEMPOTENCY_CONFLICT` | 409 | `IDEMPOTENCY_KEY_CONFLICT` |
| `ACTIVITY_NOT_FOUND` | 404 | `FLASH_SALE_ACTIVITY_NOT_FOUND` |
| `ACTIVITY_NOT_STARTED` | 409 | `FLASH_SALE_NOT_STARTED` |
| `ACTIVITY_ENDED` | 409 | `FLASH_SALE_ENDED`；`now >= endsAt` |
| `ACTIVITY_NOT_ACTIVE` | 409 | `FLASH_SALE_NOT_ACTIVE` |
| `SOLD_OUT` | 409 | `FLASH_SALE_SOLD_OUT` |
| `USER_LIMIT_REACHED` | 409 | `FLASH_SALE_USER_LIMIT_REACHED` |
| `INVALID_QUANTITY` | 400 | `FLASH_SALE_INVALID_QUANTITY` |
| `INTERNAL_STATE_INVALID` | 503 | `SECKILL_STATE_INVALID` |

Lua 原始错误、Redis 地址、Key、Java 类名和堆栈不会进入 Problem Details。

## 6. Stream 事件 Schema v1

每次 `ACCEPTED` 恰好 `XADD` 一条字段完整的事件；重放、冲突、售罄和所有拒绝不新增事件。

| 字段 | 约束 |
| --- | --- |
| `schemaVersion` | `"1"` |
| `eventType` | `RESERVATION_ACCEPTED` |
| `eventId` | `evt_` + 32 lowercase hex |
| `reservationNo` | `rsv_` + 32 lowercase hex |
| `activityId`, `userId`, `productId` | 正十进制字符串 |
| `quantity` | 正整数，不超过活动 per-User limit |
| `unitPrice` | 两位小数字符串；币种 `currency=CNY` |
| `status` | `RESERVED` |
| `requestId` | 首次请求的 Request ID |
| `occurredAtMs` | Redis `TIME` 计算的 epoch milliseconds |
| `activityVersion` | 装载的 MySQL activity version |
| `idempotencyKeyHash` | 原 Key 的 SHA-256 lowercase hex |
| `requestFingerprint` | v1 规范化请求 SHA-256 |

Stream 不携带 Token、Cookie、Email、Username 或原始 Idempotency-Key。

## 7. 本地调用与对账

启动基础设施和 app 前生成认证密钥；随后以 Administrator Access Token 装载：

```powershell
$adminToken = '<administrator-access-token>'
$activityId = '7001'
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8088/admin/api/v1/flash-sales/$activityId/load" `
  -Headers @{Authorization="Bearer $adminToken"; 'X-Request-Id'='load-7001'}
```

以 User Access Token 预约：

```powershell
$userToken = '<user-access-token>'
$headers = @{
  Authorization = "Bearer $userToken"
  'Idempotency-Key' = 'demo-reservation-0000000000000001'
  'X-Request-Id' = 'reserve-7001-user'
}
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/flash-sales/$activityId/reservations" `
  -Headers $headers -ContentType 'application/json' -Body '{"quantity":1}'
```

容器设置了 `REDISCLI_AUTH`，可查询：

```powershell
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  HGETALL 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:meta'
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  GET 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:stock'
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  XLEN 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations'
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  XRANGE 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations' - +
```

再次调用 Administrator load 会返回当前 Redis/MySQL/Stream 对账结果，但不会重置库存。SQL 检查
TASK-07 热路径没有创建数据库事实：

```powershell
docker compose --env-file .env.example exec -T mysql sh -c `
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N --user=root --database="$MYSQL_DATABASE" -e "SELECT (SELECT COUNT(*) FROM sale_reservation),(SELECT COUNT(*) FROM sales_order),(SELECT COUNT(*) FROM outbox_event)"'
```
