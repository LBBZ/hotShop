# HotShop HTTP API v1 契约

> TASK-04 契约。OpenAPI 必须由运行中的 Spring 应用生成；本文件解释跨接口规则，不代替运行时
> OpenAPI JSON。

## 1. 正式边界与一次性版本升级

正式接口只有以下三个根边界：

- `/api/v1`：匿名能力和登录 User 能力；
- `/admin/api/v1`：Administrator 能力；
- `/agent/api/v1`：预留给 Agent 进程的边界。本任务不创建任何虚构 Agent 业务接口。

`/portal/**` 和旧 `/admin/**` 已作为一次性 v1 升级移除，不保留双路由。MockMvc 契约测试以真实
Spring Security 验证旧路径在已认证请求下返回 404；生成的三个正式 OpenAPI JSON 也不包含旧路径。
调用方必须一次性迁移：

| 旧能力 | v1 |
| --- | --- |
| `/portal/products/{id}` | `GET /api/v1/products/{productId}` |
| `/portal/products/page`、`/all`、`/search` | `GET /api/v1/products` |
| `/portal/users/me` | `GET /api/v1/users/me` |
| `/portal/orders/add` | `POST /api/v1/orders` |
| `/portal/orders/page`、`/search` | `GET /api/v1/orders` |
| `/portal/auth/*` | `/api/v1/auth/*` |
| `/admin/products/add`、`/{id}`、`/search` | `/admin/api/v1/products[/{productId}]` |
| `/admin/orders/{id}`、`/user/{id}`、`/search` | `/admin/api/v1/orders[/{orderId}]` |
| `/admin/users/search` | `GET /admin/api/v1/users` |
| `/admin/auth/*` | `/admin/api/v1/auth/*` |

当前接口：

- public：注册、登录、商品详情、商品列表/搜索；
- user：登出、刷新令牌、当前 User、创建普通 Order、本人 Order 列表、Redis Lua 秒杀 Reservation；
- admin：登录、登出、Catalog Product CRUD/列表、Order 详情/列表、User 列表、只读审计日志查询、
  审计化 Flash Sale Activity 装载/校验。

Controller 的 HTTP 签名只使用 `*Request`、`*Response` 和 `CursorPageResponse` DTO。持久化实体只在
Controller 内部与领域服务之间使用，不进入请求/响应签名或 OpenAPI schema。

### 1.1 TASK-05 身份域与令牌生命周期

认证边界在签名验证阶段按 issuer、audience、`typ`、`kid` 和独立公钥集合隔离，不能靠
`role` 字符串补救错误 audience：

| 调用身份 | 唯一 HTTP 边界 | JWT `typ` | audience | 最长 TTL | 可刷新 |
| --- | --- | --- | --- | --- | --- |
| User Access | `/api/v1/**` | `user-access+jwt` | `hotshop-portal-api` | 15 分钟 | 通过 User Refresh Session |
| Administrator Access | `/admin/api/v1/**` | `administrator-access+jwt` | `hotshop-admin-api` | 15 分钟 | 通过独立 Admin Refresh Session |
| Agent Delegation | `/agent/api/v1/**` | `agent-delegation+jwt` | `hotshop-agent-api` | 5 分钟 | 否 |
| Service Identity client assertion | 仅 token exchange 的客户端证明 | `client-auth+jwt` | `hotshop-agent-token-exchange` | 60 秒 | 否 |

User、Administrator、Agent Delegation 和 Agent Service client assertion 分别使用独立 issuer、audience
与 key set。Access JWT 固定 RS256，必须同时验证 `alg`、`kid`、签名、`iss`、`aud`、`typ`、稳定
User ID 形式的 `sub`、`iat`、`nbf`、`exp`、`jti`；允许时钟偏差 30 秒。拒绝 `none`、错误算法、
未知 `kid`、跨 issuer/audience、尚未生效、过期和签名篡改令牌。Principal 完全从验证后的声明构造，
正常请求不查询数据库；禁用 User 或权限变更最多受剩余 Access TTL 影响。高风险即时撤销可将
SHA-256(`jti`) 加入 Redis denylist，绝不保存原 JWT。

认证端点：

| 方法与路径 | 凭据与 CSRF | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/auth/login` | username/password | body 返回 User Access；设置 User Refresh/CSRF cookies |
| `POST /api/v1/auth/refresh` | User Refresh cookie + CSRF cookie + `X-CSRF-Token` | 原子轮换；body 返回新 Access；替换两个 cookies |
| `POST /api/v1/auth/logout` | 有 Refresh cookie 时必须提供匹配 CSRF；Access 可选 | 撤销 family，清 cookies；有效 Access `jti` 立即 denylist |
| `POST /admin/api/v1/auth/login` | username/password，且角色必须是 Administrator | body 返回 Administrator Access；设置独立 Admin cookies |
| `POST /admin/api/v1/auth/refresh` | Admin Refresh cookie + Admin CSRF cookie/header | 独立 family 原子轮换 |
| `POST /admin/api/v1/auth/logout` | 与 User logout 同语义但只处理 Admin family | 撤销并清理 Admin cookies |
| `POST /agent/api/v1/auth/token-exchange` | body 同时提交 User subject token、Agent client assertion、scopes | 返回短期 Agent Delegation |

所有 login/refresh/logout/token-exchange 成功或安全失败响应均不得回显凭据；login/refresh 响应包含
`Cache-Control: no-store` 与 `Pragma: no-cache`。Access Token 只在 JSON body 返回，前端应仅保存在
内存。Refresh Token 是至少 256-bit CSPRNG opaque 值，数据库只保存 SHA-256 hash。

Cookie 契约：

| 域 | Refresh cookie | CSRF cookie | Path |
| --- | --- | --- | --- |
| User | `hotshop_user_refresh`（HttpOnly） | `hotshop_user_csrf`（前端可读） | `/api/v1/auth` |
| Administrator | `hotshop_admin_refresh`（HttpOnly） | `hotshop_admin_csrf`（前端可读） | `/admin/api/v1/auth` |

四个 cookie 均为 host-only、`SameSite=Strict`。生产默认 `Secure=true`；只有显式本地 HTTP 配置
`HOTSHOP_SECURE_COOKIES=false` 可关闭。refresh/logout 使用双提交 CSRF，并以常量时间比较 cookie
和 `X-CSRF-Token`。refresh 不要求 Access Token；即使 Authorization 中的 Access 已过期，只要
Refresh Session 有效仍可轮换。

轮换事务使用 `SELECT ... FOR UPDATE` 锁定 hash 命中的当前记录，将其置为 `ROTATED` 后插入唯一
successor。`UNIQUE(parent_token_id)` 保证数据库最多一个后继。两个并发 refresh 中只有一个成功；
loser 观察到旧 token 已轮换后按 reuse 处理，将旧 token 标记 `REUSED`、撤销 family 中全部
`ACTIVE` token，并在同一事务写入脱敏 `REFRESH_TOKEN_REUSE_DETECTED` 事件。因此并发竞争的最终
安全语义是 family revoked，绝不静默保留两条有效链。已撤销、过期或未知 Refresh Token 返回统一
401；logout 可重复调用。

Agent token exchange 仅接受 User Access 作为 subject token，并同时验证固定 client ID
`hotshop-agent-service` 的 RS256 client assertion。assertion 必须具有独立 issuer/audience、正确
`typ`、`sub`、`iat`、短 `exp`、`jti`、`kid` 和签名；Redis 以 hash(`jti`) 的 `SET NX EX` 在不超过
assertion 剩余有效期内防重放。scope 请求必须是下列 allowlist 的子集，否则整个请求拒绝：
`catalog:read`、`orders:self:read`、`reservations:self:read`。库存/价格写入、用户管理、Administrator
写操作、审计、密钥和权限管理永不进入 Agent scope。签发的 Agent Delegation 含 delegated User
`sub`、`azp=hotshop-agent-service` 与显式 `scope`，不含 Administrator role，也不能调用 Portal 或
Admin 边界。本任务不创建 Agent 业务成功接口。

授权矩阵的实现结果：

| 凭据 | Portal `/api/v1/**` | Admin `/admin/api/v1/**` | Agent `/agent/api/v1/**` |
| --- | --- | --- | --- |
| User Access | 按本人资源和 User authority | 认证阶段 401 | 认证阶段 401 |
| Administrator Access | 认证阶段 401 | 按显式管理 permission | 认证阶段 401 |
| Agent Delegation | 认证阶段 401 | 认证阶段 401 | 同时检查 `typ`/audience/`azp`/delegated User/scope |

登录在 BCrypt 前执行粗粒度可信客户端 IP 桶，失败后记录 IP+username hash 和失败细粒度桶；
User、Administrator、refresh 与 exchange 使用不同命名空间/限额。只有显式开启可信代理并将
immediate peer 的 IP 字面量列入 allowlist 时才读取 `X-Forwarded-For`；应用把 immediate peer
加入链尾，从右向左跳过连续可信代理，选取第一个不可信 IP。hostname、DNS 解析、空/非法元素、
多行、超过 1024 字符或 32 hops 的 header 整体忽略。计数采用 Redis 原子 Lua `INCR` + 首次 `EXPIRE`；
key 仅含最小化 IP hash/username hash并设置 TTL。认证写入口的 Redis 不可用策略为 fail-closed：
返回脱敏 503；429 必须包含 `Retry-After`。Refresh Session 的事实来源始终是 MySQL，不是 Redis。

## 2. JSON 标量规则

- 编码为 UTF-8，普通成功响应使用 `application/json`。
- 时间使用 RFC 3339 `date-time`。响应统一输出 UTC（`Z`）；时间筛选接受带时区/偏移的值并转换为
  UTC 后查询。数据库 `DATETIME(6)` 的无时区值按 UTC 存储和解释：MySQL global/session、
  Connector/J connection time zone、三个 Java 进程和超时任务时钟都固定为 UTC。
- 金额在 Java 中始终是 `BigDecimal`，数据库是 `DECIMAL(19,2)`。JSON 金额是正则
  `^(0|[1-9][0-9]*)\.[0-9]{2}$` 的字符串，例如 `"6999.00"`；写请求拒绝 JSON number，
  不经过 `float`/`double`。
- JSON 中的 BIGINT ID 是正十进制字符串，例如 `"123"`，防止 JavaScript 精度丢失。BIGINT path/
  query 参数在 OpenAPI 中同样声明为 `type: string`、正则 `^[1-9][0-9]{0,18}$`，即使 Java
  Controller 内部继续绑定为 `Long`，生成的 TypeScript URL 参数也不得是 `number`。`orderId`
  是 1–64 位 `[A-Za-z0-9_-]` 字符串，并在服务端进入业务方法前校验。
- 枚举只接受/返回 OpenAPI 中列出的全大写符号，例如 `PENDING`、`CANCELED`、`ROLE_USER`。
- schema 标为 required 的字段始终存在。数据库可空或非适用字段使用“省略”语义，不输出 JSON
  `null`；集合返回空数组，不返回 `null`。游标末页的 `nextCursor` 被省略，`hasMore=false`。
- 未知 JSON 字段的兼容策略沿用 Spring/Jackson 默认宽松读取；服务端不会在响应中回显未知字段。

## 3. 稳定游标分页

统一参数：

- `limit`：默认 20，范围 1–100；
- `cursor`：不透明、URL-safe、带版本和列表 scope 的服务端游标；调用方不得解析或跨列表复用；
- 响应：`items`、可选 `nextCursor`、`hasMore`。

实现是数据库 keyset 查询，不调用 PageHelper，也不接受页码或 offset：

| 列表 | 稳定排序 | 下一页条件 |
| --- | --- | --- |
| Catalog Product | `product_id ASC` | `product_id > cursor.productId` |
| User Order | `created_at DESC, order_id DESC` | 复合键严格小于游标 |
| Admin Order | `created_at DESC, order_id DESC` | 复合键严格小于游标 |
| Admin User | `created_at DESC, user_id DESC` | 复合键严格小于游标 |
| Admin Audit Log | `occurred_at DESC, audit_id DESC` | 复合键严格小于游标 |

排序包含唯一 ID，因此相同时间值不会产生不确定顺序。数据库集成测试固定多个相同
`created_at` 的 Order，在第一页之后并发插入排序位于游标之前的新 Order，验证第二页不重复、不跳过
原有游标之后的记录。游标与筛选条件由调用方共同保持；筛选变化时必须丢弃旧游标重新开始。

### 3.1 管理员审计日志

`GET /admin/api/v1/audit-logs` 是唯一审计业务 API，只接受 Administrator Access。User Access、
Agent Delegation、匿名请求和错误 audience 的令牌都被拒绝；不存在审计日志的 POST、PUT、PATCH、
DELETE 或清空端点。

查询支持 `occurredFrom`、`occurredTo`、`actorType`、`actorId`、`action`、`resourceType`、
`resourceId`、`result`、`limit` 和 `cursor`。时间按第 2 节规则转换为 UTC。游标包含筛选 scope；
改变任一筛选条件后复用旧游标返回 `CURSOR_INVALID`，不会把不同调查条件的页拼接在一起。

响应项包含 `auditId`、actor、可选 delegated actor、action、resource、result、request ID、
trace ID、source、`occurredAt` 和脱敏 `stateSummary`。`stateSummary` 只来自服务端强类型摘要，
不回显管理写请求、凭据、完整提示词、思维链或原始异常消息。

管理写入的提交语义：

- 成功的 Catalog Product 管理写与 SUCCESS 审计 INSERT 共用本地事务；审计失败则业务回滚；
- 业务写失败后，FAILURE 审计用独立事务提交，只记录稳定原因码；
- 登录成功和 refresh reuse 继续与 Refresh Session 事务共同提交；登录失败用独立事务记录；
- Agent Delegation 只有在审计 INSERT 成功后才作为成功返回。

## 4. Problem Details

所有 API 错误使用 RFC 9457/Spring `ProblemDetail` 语义，媒体类型精确为
`application/problem+json`。字段：

```json
{
  "type": "https://hotshop.local/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid",
  "instance": "/api/v1/products",
  "code": "VALIDATION_FAILED",
  "requestId": "client-request-1",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "violations": [
    {"field": "getProducts.limit", "code": "Min", "message": "must be greater than or equal to 1"}
  ]
}
```

`code` 是客户端分支依据，`title/detail` 只用于人读。已定义的稳定类别至少包括：

- `VALIDATION_FAILED`、`PARAMETER_INVALID`、`PARAMETER_MISSING`、`MALFORMED_JSON`、
  `CURSOR_INVALID`；
- `AUTHENTICATION_REQUIRED`、`ACCESS_DENIED`；
- `RESOURCE_NOT_FOUND`；
- `METHOD_NOT_ALLOWED`（405，同时保留 `Allow`）、`NOT_ACCEPTABLE`（406）、
  `UNSUPPORTED_MEDIA_TYPE`（415）；
- `USERNAME_CONFLICT`、`EMAIL_CONFLICT`、`DATA_CONFLICT`、`INVENTORY_CONFLICT`；
- `RATE_LIMITED`（同时返回 `Retry-After`）；
- `AUTHENTICATION_SERVICE_UNAVAILABLE`（认证依赖不可用时返回脱敏 503）、`INTERNAL_ERROR`。

校验错误提供不含 rejected value 的结构化 `violations`。未知异常响应和生产错误日志不写入原始异常
消息、堆栈、SQL、表名、内部类名、Token 或密码；外部只得到固定的 `INTERNAL_ERROR` detail。

## 5. Request ID 与 Trace ID

- 客户端可发送 `X-Request-Id`，格式为 1–64 位
  `[A-Za-z0-9][A-Za-z0-9._:-]*`；非法或缺失时服务端生成 UUID。
- 有效 Request ID 在响应头 `X-Request-Id`、MDC `requestId` 和错误体 `requestId` 中完全一致。
- Trace ID 表示分布式 trace，不是 Request ID。服务端从合法 W3C `traceparent` 提取 32 位小写
  hex trace ID；缺失/非法时生成新的非零 128-bit ID。
- Trace ID 在 `X-Trace-Id`、MDC `traceId` 和错误体 `traceId` 中一致。当前边界保留
  `traceparent` 传播语义，TASK-13 接入 Tempo 时不得把 Request ID 复用为 Trace ID。

## 6. Idempotency-Key

运行时 OpenAPI 的 components 定义 `Idempotency-Key` 和 `Idempotency-Replayed`。
`POST /api/v1/flash-sales/{activityId}/reservations` 是第一个正式启用的操作；幂等结果保存在
`redis-seckill` DB 0，保留 24 小时。当前 `POST /api/v1/orders` 仍不宣称支持幂等键。

未来某个写操作启用时必须同时满足：

- key 是 16–128 位可见 ASCII，正则
  `^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$`；
- scope 是“已认证 User + Flash Sale Reservation operation”；v1 fingerprint 绑定 activityId 与
  quantity，Key 本身以 SHA-256 进入 Redis；
- 相同 key、scope 和 fingerprint 重放首次完成的 HTTP status、业务 body 与契约相关 headers，并返回
  `Idempotency-Replayed: true`；
- 相同 key/scope 但 fingerprint 不同返回 409 `IDEMPOTENCY_KEY_CONFLICT`；
- 首次请求仍处理中时返回 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` 和 `Retry-After`；
- 5xx 或 Lua 未完整提交不得缓存为成功重放；TASK-07 保留期为 24 小时；
- Reservation 接口用单个 Lua 同时仲裁业务事实、幂等结果与 Stream 事件；其他操作必须完成自己的
  一致性和持久化测试后，才能把 component 挂到具体操作。

秒杀 Reservation 成功返回 202：

```json
{
  "reservationNo": "rsv_0123456789abcdef0123456789abcdef",
  "activityId": "7001",
  "status": "RESERVED",
  "requestId": "reserve-7001-user"
}
```

相同 User/Key/fingerprint 重放相同 body 并返回 `Idempotency-Replayed: true`；相同 Key 更换
activityId 或 quantity 返回 409 `IDEMPOTENCY_KEY_CONFLICT`。服务端只信任 Access Principal 的
User ID，请求 body 不接受 userId。详细 Redis/Lua/Stream 契约见
`docs/architecture/flash-sale-reservation.md`。

TASK-08 增加本人 Reservation 状态查询：

```text
GET /api/v1/flash-sales/{activityId}/reservations/{reservationNo}
credential: User Access
authority: ROLE_USER
```

`activityId` 是正 BIGINT path 字符串，`reservationNo` 必须匹配
`^rsv_[0-9a-f]{32}$`。服务端 User ID 只来自已验证 Principal，并先按
`activityId + reservationNo + userId` 查询 MySQL；尚未落库时才读取经过 schema 和所有权验证的
Redis Reservation。未知资源与属于其他 User 的资源统一返回 404，不泄露 Reservation 是否存在。
Administrator Access、Agent Delegation 和匿名请求都不能调用。

200 响应示例：

```json
{
  "reservationNo": "rsv_0123456789abcdef0123456789abcdef",
  "activityId": "7001",
  "status": "ORDER_CREATED",
  "orderId": "ord_0123456789abcdef0123456789abcdef",
  "quantity": 1,
  "reservedAmount": "19.90",
  "currency": "CNY"
}
```

`status` 可为 `RESERVED`、`ORDER_CREATED`、`COMPENSATING`、`COMPENSATED`、`EXPIRED` 或
`CANCELED`；不适用时省略 `orderId`。金额继续遵循两位小数字符串契约。该接口只暴露用户自己的
业务状态，不提供 Pending、处理账本、补偿、重放、修复或对账操作；这些高风险能力也不进入 Agent
scope。消费、ACK 和最终一致性设计见
`docs/architecture/stream-order-processing.md`。

## 7. OpenAPI、客户端与兼容门禁

运行时分组 URL：

- portal：`/v3/api-docs/public`、`/v3/api-docs/user`；
- admin：`/v3/api-docs/admin`；
- `/v3/api-docs/agent-boundary` 只证明 `/agent/api/v1/**` 的保留分组，不作为业务 client 输入。

生成命令：

```powershell
.\script\generate-api-client.ps1
```

脚本用 Docker 中的 Java 21 和仓库 Maven Wrapper执行测试/打包，启动真实 portal/admin jar，抓取
运行时 JSON 到 `target/openapi/{public,user,admin}.json`，再仅对运行时 JSON 做稳定 key 排序和空白
规范化，最后用固定 OpenAPI Generator 7.14.0 生成：

```text
target/generated-sources/typescript/
  public/
  user/
  admin/
```

`target/` 全部是可删除、可重复生成的输出边界；生成文件带有 `Do not edit` 声明，不得手工修改。
契约的 server 是合法的同源相对 URL `/`。OpenAPI Generator 7.14.0 在其 OpenAPI 3.1 beta 路径中会
为相对 server 输出 localhost fallback 警告；集成生成 client 时必须通过其 `Configuration.basePath`
显式传入当前环境的 API origin，不能把生成器 fallback 当成生产地址。
有意升级基线时先评审 API diff，再运行：

```powershell
.\script\update-openapi-baseline.ps1
```

日常兼容检查：

```powershell
python .\script\check_openapi_compatibility.py
```

基线位于 `docs/api/openapi-baseline/`，也来自运行时 JSON。门禁保守检查 path/operation、参数、request/
response schema 和 component，至少拒绝删除路径、删除字段、改变类型、把可选字段收紧为 required。
HotShop 额外不变量会拒绝 `productId`/`userId` URL 参数退回 `integer/int64`，或 `orderId` path
丢失长度和字符集约束。

## 8. TASK-09 管理员 Outbox 运维契约

以下接口只接受 Administrator Access；User Access、匿名请求和 Agent Delegation 在身份边界被拒绝，
Agent OpenAPI 和工具面不提供等价能力：

- `GET /admin/api/v1/outbox/failed?limit=20&cursor=...`：按 `outbox_id DESC` 做稳定 keyset 分页；
- `POST /admin/api/v1/outbox/{eventId}/replay`：body 为 `{"reason":"..."}`，仅接受 `FAILED`。

失败列表只返回 eventId、eventType、aggregate type/id、累计/本轮 attempts、人工重放次数、脱敏
failure category 与时间，不返回 payload、`last_error`、凭据、SQL 或堆栈。重放成功返回 202，只修改
MySQL 状态并追加 append-only `audit_log`；RabbitMQ 发布由 task 异步完成。`NEW`、`PUBLISHING` 和
`PUBLISHED` 均返回 409 `OUTBOX_NOT_FAILED`，不能借此重复发布已经完成的事件。
