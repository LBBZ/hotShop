# Redis Lua 秒杀预约设计

## 1. 责任边界

预约入口只接收 Reservation。`POST /api/v1/flash-sales/{activityId}/reservations` 在
`redis-seckill` 的一个 Lua 脚本中完成校验、库存预扣、Reservation/User/幂等事实写入和 `XADD`，
然后返回 `202 Accepted`。预约业务热路径不写 MySQL、不创建 Order、不调用 RabbitMQ、模型或 Agent，也没有
Java 全局锁或分布式锁。

身份校验仍通过 Java `JwtFilter` 查询 MySQL 的 Access 撤销标记，因此完整 HTTP 请求不应被描述为无数据库依赖。
MySQL 是最终交易事实来源。Redis Stream 中的 `RESERVATION_ACCEPTED` 由 `task` 消费，负责
Pending List/重试/认领、创建 `sale_reservation` 与 `sales_order`、补偿和对账。
`POST /api/v1/orders` 是独立的同步、幂等普通购买接口；秒杀使用预约入口。

## 2. 固定双 Redis 连接

应用启动时只创建以下具名 Lettuce 工厂和字符串客户端，两个工厂都固定 `database=0`：

| 实例 | Bean | 数据 |
| --- | --- | --- |
| `redis-cache` | Primary `cacheRedisConnectionFactory` / `cacheStringRedisTemplate` | 认证/交易限流、普通缓存；Python Agent 另用它保存短期会话；可淘汰、可重建 |
| `redis-seckill` | `seckillRedisConnectionFactory` / `seckillStringRedisTemplate` | 活动、库存、Reservation、User 占位、幂等结果、Stream；`noeviction`、AOF+RDB |

安全组件按类型注入时得到 Primary cache 客户端；秒杀服务必须用
`@Qualifier("seckillStringRedisTemplate")`。不再存在 `RedisTemplateGenerator`，请求期间不会创建
`LettuceConnectionFactory`。Access 撤销与 Agent assertion 防重放使用 MySQL 持久安全标记。

## 3. Key、hash tag 与生命周期

公共前缀是 `hotshop:seckill:v1:{hotshop-seckill-v1}`。所有 Lua Key 使用同一个 hash tag，因而在
Redis Cluster 中计算到同一个 slot。使用全局 tag 是为了让 User 级 Idempotency-Key 能同时约束不同
activityId；代价是当前秒杀写流量集中在一个 slot，后续若要分片，必须先重新设计跨活动幂等
协议并升级 Key/脚本版本，不能直接改 v1。

Key 不包含 Access Token、Cookie、Email、Username 或原始 Idempotency-Key。Idempotency-Key 先做
SHA-256；User 使用稳定数值 ID。

| 事实 | Key 模板 | 类型 | TTL / 生命周期 |
| --- | --- | --- | --- |
| 活动元数据 | `...:activity:{activityId}:meta` | Hash | 首次发布 / 无预约配置替换时设为 `endsAt + 7d`；幂等核验不延长 |
| 可售库存 | `...:activity:{activityId}:stock` | String integer | `endsAt + 7d` |
| User 有效占位 | `...:activity:{activityId}:user:{userId}:reservation` | String reservationNo | 接受时计算 `endsAt + 7d` |
| 幂等结果 | `...:idempotency:user:{userId}:{sha256(key)}` | Hash | 24h |
| Reservation | `...:activity:{activityId}:reservation:{reservationNo}` | Hash | 接受时计算 `endsAt + 7d` |
| 活动 Stream | `...:activity:{activityId}:reservations` | Stream | 无 TTL、无 `MAXLEN`；消费 ACK 不删除原始记录，目前无自动截断/归档 |
| Stream Registry | `...:registry:reservation-streams` | Set | 无 TTL；由装载 Lua v3 原子登记，消费者不使用 `KEYS` |
| 装载 staging | `...:activity:{activityId}:load:{loadId}:{meta\|stock}` | 临时 Hash/String | 同一 Lua 内 rename 或删除，不跨请求保留 |

预约 Lua 只按计算好的 Key 做 O(1) 访问，不使用 `KEYS` 或 `SCAN`。对账的 `XRANGE` 出现在
管理装载/校验和后台对账路径，不属于预约 Lua 热路径。

## 4. MySQL → redis-seckill 装载

Administrator 调用：

```text
POST /admin/api/v1/flash-sales/{activityId}/load
authority: PERM_ADMIN_FLASH_SALE_LOAD
```

服务读取 `flash_sale_activity` 并 LEFT JOIN `catalog_product`，检查：

- Catalog Product 存在、未删除且 `ACTIVE`，引用 ID 一致；
- 活动价与 Catalog 价格非负、两位小数，活动价不高于 Catalog 价格；
- 配额大于 0、数据库可用库存处于 `[0,total]`、Catalog stock 非负；
- per-User limit 大于 0且不超过总库存；
- `endsAt > startsAt`，status 属于数据库允许集合，version 非负。

当前使用 [装载 Lua v3](../../domain/src/main/resources/redis/load-flash-sale-activity-v3.lua)，保留 v1 / v2 文件。
商品、活动价、配额、每人限制、状态和时间窗共同定义已发布的 offer。建单和回库推进 MySQL 行版本，
但并不发布新的 offer，详见 [ADR-009](adr/ADR-009-preserve-loaded-offer.md)。

- 配置相同且数据库行版本不低于已装载版本：返回 `IDEMPOTENT`，保留初始余额、当前 Redis 库存、offer 版本和所有预约。
- 新建或替换库存必须是未使用的活动：没有数据库预约、没有 Stream / 库存变动历史，实际 / expected 余额一致，配额不超过当前 Catalog stock。初始可用量允许小于总配额，按其实际值建立 `S0`。替换已有配置还要求更高行版本以及未受损的原始 Redis 余额。
- 已有预约的配置变更：`RESERVATIONS_EXIST`；已释放的预约仍算历史。清空 Stream 不能绕过已有库存 revision 或数据库预约保护。
- 低于已装载版本：`STALE_VERSION`；同版本更改配置、错误类型、仅余库存而缺少元数据：`INTERNAL_STATE_INVALID`。
- 普通加载不提供历史活动的增减配额、暂停 / 恢复或丢失事实恢复协议。全新初始化失败返回 `ACTIVITY_INVALID`，不会修正库存来迎合配置。

`LOADED` 与 `IDEMPOTENT` 都原子登记 Stream Registry 和对账索引。所有 Key 继续使用同一 hash tag。
响应 `databaseVersion` 是当前 MySQL 行版本；`redisVersion` 及预约事件中的 `activityVersion` 是已发布 offer 的来源版本，
前者大于后者可以是正常交易推进，不代表配置未同步。

核验使用 [只读观察 Lua](../../domain/src/main/resources/redis/inspect-loaded-activity-v1.lua)，最多检查 1,000 条 Stream 事件，
按 reservationNo 去重并核对事件与预约的不可变字段。`RESERVED`、`ORDER_CREATED`、`COMPENSATING` 占用库存；
`COMPENSATED`、`PAYMENT_EXPIRED` 保留历史但不再占用库存，已释放 User 占位可以属于后续预约。
令 `S0` 为装载初始余额、`E` 为 Redis 有效预约数量、`M` 为 MySQL `ORDER_CREATED` 预约数量，则检查：

```text
S0 = Redis 当前可用库存 + E
S0 = MySQL 活动可用库存 + M
MySQL 活动可用库存 = expected_available_stock
MySQL 商品可用库存 = expected_stock
```

商品余额由普通订单和所有活动共享，不能拿某个活动的初始商品库存减该活动销量来核对。MySQL 查询在同一语句读取余额和已承诺数量，
Redis 观察前后再次比较 MySQL 事实，读到变动时不声明一致。这仍是有限时点的观察，不是跨 MySQL / Redis 的原子快照或交易完成证明。
MySQL 已回库而 Redis 超时投影尚未执行时，两侧可以各自守恒；加载不会代替投影返还库存。

响应保留两侧库存、Stream 数、已检查的不同预约数、历史预约 quantity 及 `consistent`。
历史 quantity 包括已释放预约，不能直接当作 `E`。超过扫描上限时不执行完整 `XRANGE`，返回 `consistent=false`，
`detail` 明确核验未完成并指向后台对账；此时预约统计为已检查数量，不是历史总数。无效引用、余额差异或观察中变动也不会显示核验成功。
MySQL 的已承诺数量聚合仍按活动索引查询，未宣称其成本与历史规模无关。

后台对账也有单轮去重预算，受到持续写入干扰或超过预算时会记录核验未完成，不能把转交后台理解为必然获得一致结论，见[有界守恒扫描](stream-order-processing.md#有界守恒扫描)。

管理调用由 Administrator 身份强制授权，并把结果/失败原因、request ID、trace ID 和核验摘要写入只追加 `audit_log`。

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
| `traceparent`、`tracestate` | 与接受预约同次 `XADD` 写入的 W3C 关联上下文；无值时使用空字符串 |
| `occurredAtMs` | Redis `TIME` 计算的 epoch milliseconds |
| `activityVersion` | 已发布 offer 的来源版本；同配置重复加载时保持不变 |
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
  -Headers @{Authorization="Bearer $adminToken"; 'X-Request-Id'='load-7001'} `
  -ContentType 'application/json' -Body '{"reason":"复查活动装载与库存"}'
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
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli `
  HGETALL 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:meta'
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli `
  GET 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:stock'
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli `
  XLEN 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations'
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli `
  XRANGE 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations' - +
```

再次调用 Administrator load 会返回当前 Redis/MySQL/Stream 对账结果，但不会重置库存。以下 SQL
用于观察异步持久化总量，不应把“始终为零”作为预约成功断言：

```powershell
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T mysql sh -c `
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N --user=root --database="$MYSQL_DATABASE" -e "SELECT (SELECT COUNT(*) FROM sale_reservation),(SELECT COUNT(*) FROM sales_order),(SELECT COUNT(*) FROM outbox_event)"'
```

上述三张表由异步消费者写入。调查某次预约需按 reservationNo/orderId 关联记录。Registry、
消费者组、Pending、处理账本、补偿和对账命令见
`docs/architecture/stream-order-processing.md`。
