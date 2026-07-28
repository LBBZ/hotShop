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
- user：登出、刷新令牌、当前 User、创建 Order、本人 Order 列表；
- admin：登录、登出、Catalog Product CRUD/列表、Order 详情/列表、User 列表。

Controller 的 HTTP 签名只使用 `*Request`、`*Response` 和 `CursorPageResponse` DTO。持久化实体只在
Controller 内部与领域服务之间使用，不进入请求/响应签名或 OpenAPI schema。

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

排序包含唯一 ID，因此相同时间值不会产生不确定顺序。数据库集成测试固定多个相同
`created_at` 的 Order，在第一页之后并发插入排序位于游标之前的新 Order，验证第二页不重复、不跳过
原有游标之后的记录。游标与筛选条件由调用方共同保持；筛选变化时必须丢弃旧游标重新开始。

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
- `INTERNAL_ERROR`。

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

## 6. Idempotency-Key 预留语义

运行时 OpenAPI 的 components 定义了尚未挂到任何操作的 `Idempotency-Key` 和
`Idempotency-Replayed`。本任务没有伪造存储或重放能力；包括当前 `POST /api/v1/orders` 在内的写接口
在持久化实现完成前均明确不宣称支持幂等键。

未来某个写操作启用时必须同时满足：

- key 是 16–128 位可见 ASCII，正则
  `^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$`；
- scope 是“已认证 actor + HTTP operation”，并持久化规范化请求 fingerprint；
- 相同 key、scope 和 fingerprint 重放首次完成的 HTTP status、业务 body 与契约相关 headers，并返回
  `Idempotency-Replayed: true`；
- 相同 key/scope 但 fingerprint 不同返回 409 `IDEMPOTENCY_KEY_CONFLICT`；
- 首次请求仍处理中时返回 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` 和 `Retry-After`；
- 5xx 或事务未提交不得缓存为成功重放；保留期必须由具体业务契约声明；
- 实现与业务事务的一致性、并发仲裁和清理策略必须有持久化测试后，才能把 component 挂到具体操作。

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
