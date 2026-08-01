# HotShop 当前架构基线

> 快照范围：TASK-00，2026-07-26。本文描述的是当前工作区事实，不代表目标架构已经实现。
> `Dockerfile`、`docker-compose.yml`、`.dockerignore` 与 `docker/rabbitmq/` 在 TASK-00
> 开始前已有用户未提交修改；下文按当前工作区盘点，但 TASK-00 未改写这些文件。
> 数据库第 4 节及相关已知问题已由 TASK-02 更新到 Flyway 1.1 基线。
> TASK-09 已在 2026-08-01 更新第 1、2、3、5、6、7、8 节中与可靠消息相关的现状。

## 1. 运行时全景

HotShop 是一个 Maven 多模块、三个 Spring Boot 进程的单仓库应用。`common`、`infrastructure`、
`domain`、`security` 是共享类库，`portal`、`admin`、`task` 是可启动进程。

```mermaid
flowchart LR
    client["用户 / API 调用方"] --> portal["portal<br/>8080"]
    operator["管理员 / API 调用方"] --> admin["admin<br/>8088"]

    portal --> security["security"]
    admin --> security
    security --> domain["domain"]
    task["task<br/>8888"] --> domain
    domain --> common["common"]
    domain --> infrastructure["infrastructure"]

    portal --> mysql[("MySQL 8<br/>hotShop")]
    admin --> mysql
    task --> mysql
    portal --> redis[("单 Redis 实例")]
    admin --> redis
    task --> redis
    task --> rabbit[["RabbitMQ 4"]]
    rabbit --> task
```

当前不是微服务架构，也没有服务注册、配置中心、API 网关或分布式事务框架。portal 只在 MySQL
事务内写业务数据和 Outbox；task 从 MySQL 领取 Outbox 后向 RabbitMQ 投递，并消费普通订单超时消息。

## 2. Maven 模块与依赖

父工程为 `com.real:hotShop:0.0.1-SNAPSHOT`，当前使用 Java 17、Spring Boot 3.4.3、
MyBatis Spring Boot Starter 3.0.4。仓库有 `.mvn/` 目录，但没有 `mvnw` 或 `mvnw.cmd`。

| 模块 | 类型 | 直接项目依赖 | 主要职责 |
| --- | --- | --- | --- |
| `common` | library | 无 | 枚举、异常、分页与重试工具、共享 JWT 配置 |
| `infrastructure` | library | 无 | Redis Template 工厂、RabbitMQ 拓扑、OpenAPI、共享数据源/Redis 配置 |
| `domain` | library | `common`, `infrastructure` | 实体、MyBatis Mapper、用户/商品/订单服务 |
| `security` | library | `domain` | Spring Security、JWT、Token 黑名单 |
| `portal` | application | `security` | 用户认证、商品、用户与订单 HTTP API |
| `admin` | application | `security` | 管理员认证、用户、商品与订单 HTTP API |
| `task` | application | `domain` | Outbox 发布与普通订单 RabbitMQ 超时消费 |

因为 `security -> domain -> infrastructure/common`，`portal` 和 `admin` 会传递获得数据库、Redis、
RabbitMQ/OpenAPI 等能力；`task -> domain` 也会传递获得这些能力。

## 3. 启动入口、配置与端口

### 3.1 Java 入口

| 进程 | main class | 扫描范围 | Mapper 扫描 | 默认端口 |
| --- | --- | --- | --- | --- |
| portal | `com.real.portal.hotShopPortalApplication` | `common`, `infrastructure`, `security`, `domain`, `portal` | `com.real.domain.mapper` | 8080 |
| admin | `com.real.admin.hotShopAdminApplication` | `common`, `infrastructure`, `security`, `domain`, `admin` | `com.real.domain.mapper` | 8088 |
| task | `com.real.task.hotShopTaskApplication` | `common`, `infrastructure`, `domain`, `task` | `com.real.domain.mapper` | 8888 |

### 3.2 配置加载

- 三个进程都从各自的 `application.yml` 启动；`SPRING_PROFILES_ACTIVE` 默认为空，不会自动启用
  `dev`。
- 三个进程都导入 `classpath:config/common-db.yml`、`common-redis.yml`；portal 和 admin 还导入
  `common-jwt.yml`。
- `application-prod.yml` 只有显式激活 `prod` 时生效；`application-dev.yml` 只有显式激活
  `dev` 时生效。
- 数据源默认指向 `localhost:3306/hotShop`，Redis 默认 `localhost:6379`，
  RabbitMQ 默认 `localhost:5672`。
- Compose 内应用使用服务名 `mysql`、`redis`、`rabbitmq` 和容器端口。宿主机映射端口不同：
  MySQL 4306、Redis 7379、RabbitMQ AMQP 6672、RabbitMQ Management 15673。
  因此从宿主机直接启动 Java 进程时，必须显式覆盖基础设施端口；当前 YAML 只提供了 host
  环境变量，没有为这些宿主机映射端口提供统一启动命令。
- task 加载 RabbitMQ 拓扑、Outbox 发布器和手动 ACK 消费者。portal 排除 RabbitMQ 自动配置，
  创建普通订单时不建立 RabbitMQ 连接；admin 也不发布消息。

### 3.3 Compose 服务

当前 Compose 定义 `mysql`、`redis`、`rabbitmq`、`portal-service`、`admin-service`、
`task-service`。MySQL、Redis、RabbitMQ 使用持久卷；三个应用由根 `Dockerfile` 的 Maven
builder 阶段按模块构建。RabbitMQ wrapper 仅基于官方 management 镜像，不下载或启用第三方插件。

## 4. 数据库基线

TASK-02 已将结构事实切换到 `database/src/main/resources/db/migration`。Compose 的一次性
`database-migrator` 是唯一迁移执行者，三个 Java 进程显式关闭 Flyway 并等待迁移成功；旧
`docker/mysql/init.sql` 及其自动挂载已移除。

当前版本 `1.5` 包含 `app_user`、`catalog_product`、`flash_sale_activity`、`sale_reservation`、
`sales_order`、`sales_order_item`、`payment_order`、`refresh_token`、`outbox_event`、
`processed_event`、`audit_log`。所有跨表 ID 都由应用层校验；结构使用业务唯一键、CHECK、
NOT NULL、精确金额和查询驱动索引维持局部一致性。完整表、状态、索引和接管说明见
`docs/architecture/database-schema.md`。

旧四表可通过显式 version 0 baseline 接管；普通 migrate 不会自动接管非空未知 schema。版本 1.1
会导入兼容数据后移除旧裸表名。开发数据和压测数据位于 `database/data`，不属于生产迁移且不会自动执行。

库存扣减位于 `domain/.../ProductMapper.xml` 的 `reduceStock`：

```sql
UPDATE catalog_product
SET stock = stock - #{quantity}
WHERE product_id = #{productId}
  AND #{quantity} > 0
  AND stock >= #{quantity}
  AND deleted_at IS NULL
```

它把正数量、库存足够和未删除检查与扣减放在同一条 SQL 中，调用方以受影响行数为 0 判断失败；
表级 `stock >= 0` 再提供兜底。version 列已预留，TASK-02 不提前改造后续交易并发流程。

## 5. Redis 基线

当前只有一个 Redis 容器，但应用可按逻辑 DB 0–15 动态创建和缓存 `RedisTemplate`。
Key 使用 JDK value 序列化：

| Key 模式 | DB | 值 | TTL | 写入方 |
| --- | --- | --- | --- | --- |
| `jwt:blacklist:{sha256(token)}` | 0 | `"invalid"` | Token 剩余秒数 | `TokenBlacklistService` |
普通订单超时消费者不再使用 Redis 锁；它通过 MySQL Inbox、行锁和条件更新保证幂等业务效果。

## 6. RabbitMQ 基线

task 声明版本化、durable 的可靠消息拓扑：

| 类型 | 名称 | 配置 |
| --- | --- | --- |
| Topic exchange | `hotshop.business.events.v1` | Outbox 业务事件 |
| Direct exchange | `hotshop.order.timeout.schedule.v1` | 普通订单超时调度入口 |
| TTL queue | `hotshop.order.timeout.delay.v1` | 固定 TTL，并配置 DLX 与 routing key |
| Direct exchange / queue | `hotshop.order.timeout.ready.v1` | 到期后的手动 ACK 消费 |
| Dead-letter exchange / queue | `hotshop.order.timeout.dead.v1` | 毒消息隔离 |

portal 在订单事务中写 `ORDER_CREATED` 和 `LEGACY_ORDER_TIMEOUT_REQUESTED` Outbox。task 使用租约与
fencing 分批领取，数据库事务提交后才进行网络调用；消息持久化并使用 event ID 关联 confirm。
只有 broker ACK、无 mandatory return 且当前租约仍有效时才标记 `PUBLISHED`。普通订单超时采用
TTL + DLX，消费者事务提交后手动 ACK；旧定时扫描和 Redis 锁路径已移除。完整语义见
`docs/architecture/reliable-messaging.md`。

## 7. HTTP 接口

Spring Security 当前允许匿名访问认证入口、portal 商品接口和 OpenAPI/Swagger 静态资源；
其余请求要求认证。下表仅盘点映射，不代表权限或业务语义已经满足总纲。

### 7.1 Portal（8080）

| Method | Path | 入口 |
| --- | --- | --- |
| POST | `/portal/auth/register` | 注册 |
| POST | `/portal/auth/login` | 登录 |
| POST | `/portal/auth/logout` | 登出 |
| POST | `/portal/auth/refresh` | 刷新令牌 |
| GET | `/portal/products/{productId}` | 商品详情 |
| GET | `/portal/products/page` | 商品分页 |
| GET | `/portal/products/all` | 全部商品 |
| GET | `/portal/products/search` | 商品条件搜索 |
| GET | `/portal/users/me` | 当前用户 |
| POST | `/portal/orders/add` | 在单个 MySQL 事务中创建订单并写两个 Outbox |
| GET | `/portal/orders/page` | 当前用户订单分页 |
| GET | `/portal/orders/search` | 当前用户订单条件搜索 |

### 7.2 Admin（8088）

| Method | Path | 入口 |
| --- | --- | --- |
| POST | `/admin/auth/login` | 管理员登录 |
| POST | `/admin/auth/logout` | 管理员登出 |
| GET | `/admin/users/search` | 用户搜索 |
| POST | `/admin/products/add` | 新增商品 |
| PUT | `/admin/products/{id}` | 更新商品 |
| DELETE | `/admin/products/{id}` | 软删除商品 |
| GET | `/admin/products/{id}` | 商品详情 |
| GET | `/admin/products/search` | 商品搜索 |
| GET | `/admin/orders/{orderId}` | 订单详情 |
| GET | `/admin/orders/user/{userId}` | 用户订单 |
| GET | `/admin/orders/search` | 订单搜索 |
| GET | `/admin/api/v1/outbox/failed` | 管理员查询脱敏的 FAILED Outbox |
| POST | `/admin/api/v1/outbox/{eventId}/replay` | 管理员记录原因并重置 FAILED 事件 |

两个 Web 应用默认提供 `/v3/api-docs` 与 `/swagger-ui/**`（prod profile 关闭）。Actuator 依赖
存在，但当前没有专门的 exposure 配置。

## 8. 已知问题与后续归属

下列 TASK-00 问题在当前基线中的状态如下；TASK-09 已关闭的旧路径不再列为当前问题。

| 问题 | 代码位置 | 触发条件与当前后果 | 后续任务 |
| --- | --- | --- | --- |
| 条件扣库存只防正常正数超卖 | `domain/src/main/java/com/real/domain/mapper/ProductMapper.xml:19-23` | 正数并发扣减由条件 SQL 保护；零/负数量未校验，负数会反向增加库存 | TASK-02、TASK-08 |
| 数据库结构与 Mapper 兼容 | `database/src/main/resources/db/migration/`、`domain/.../mapper/*.xml` | TASK-02 已切换 Flyway、移除旧初始化源并用真 MySQL 测试重命名兼容；后续业务仍须实际调用应用层引用校验 | TASK-07、TASK-08 |
| 构建与跨平台编码基线不完整 | 根 `pom.xml`、缺失的 `mvnw*`/`.gitattributes` | 无 Wrapper；Git `core.autocrlf=true`；未显式按 UTF-8 读取时中文可显示为乱码 | TASK-01 |
| 仓库内存在固定 JWT 密钥和默认凭据 | `common-jwt.yml:2`、各 `application*.yml`、`docker-compose.yml` | 使用默认配置启动会复用仓库值，不满足最终密钥管理要求 | TASK-03、TASK-05 |

编码扫描结果是：当前受检文本文件都能严格按 UTF-8 解码、没有 UTF-8 BOM，也没有检测到已落盘
的常见 mojibake 文本。已观察到的乱码来自 Windows PowerShell 默认读取编码与 UTF-8 文件不一致，
不能伪称为源文件已经损坏。
