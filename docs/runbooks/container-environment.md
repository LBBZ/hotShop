# HotShop 容器环境运行手册

本文档覆盖 TASK-03 的本机数据基础设施及 TASK-02 的数据库迁移。默认 Compose 集合启动 MySQL、
一次性 `database-migrator`、`redis-cache`、`redis-seckill` 和 RabbitMQ；三个 Java 进程位于
`app` profile。

## 1. 前置条件与凭据

- Docker Engine 及 Docker Compose 可用。
- 启动 `app` profile 前先按
  [`authentication-operations.md`](authentication-operations.md) 运行
  `.\script\generate-auth-keys.ps1`；生成目录被 Git/Docker 忽略，私钥不得写入 env 或镜像。
- 不要把真实密码写入 `.env.example`。
- `.gitignore` 已忽略 `.env` 和 `.env.*`，并显式保留 `.env.example`。可以使用仓库根目录下被
  忽略的本机 env 文件；更严格隔离凭据时，仍建议复制到仓库外并通过 `--env-file` 指定。
- 直接使用 `.env.example` 仅适合一次性的本机验证，其中的 `change-me-*` 都是公开占位值。
- MySQL 的 `MYSQL_ROOT_PASSWORD` 只在空数据卷首次初始化时生效。若复用 TASK-03 前已经初始化的
  `mysql_data`，外部 env 文件必须填写该数据卷原有的 root 密码；修改 env 不会轮换数据库密码。

PowerShell 示例：

```powershell
$hotShopEnv = Join-Path $env:LOCALAPPDATA 'HotShop\compose.env'
New-Item -ItemType Directory -Force (Split-Path $hotShopEnv) | Out-Null
Copy-Item .env.example $hotShopEnv
# 编辑 $hotShopEnv，替换所有 change-me-* 值
docker compose --env-file $hotShopEnv config --quiet
```

Linux/macOS 可把文件保存到 `~/.config/hotshop/compose.env`，并使用相同的 `--env-file` 参数。
`.dockerignore` 会排除 `.env` 和 `.env.*`（保留 `.env.example`），避免凭据意外进入应用镜像上下文。

## 2. 启动方式

使用公开占位凭据的一条基础设施启动命令：

```powershell
docker compose --env-file .env.example up -d --build --wait
docker compose --env-file .env.example wait database-migrator
```

使用仓库外真实本机凭据时，将 `.env.example` 替换为上一节的 `$hotShopEnv`。默认集合不构建 Java
应用镜像，因此不会被并行中的 Maven 改动阻塞。第二条命令必须返回 0；Compose 的 `up --wait`
可能在一次性 migrator 尚未结束时返回，不能省略显式 `wait`。

完整应用采用显式 `app` profile：

```powershell
docker compose --env-file .env.example --profile app up -d --build
```

TASK-09 使用官方 RabbitMQ management 镜像支持的 TTL 队列 + DLX，不安装第三方插件。portal
不加载 RabbitMQ 自动配置，也不依赖 RabbitMQ 健康状态；task 独占消息发布与消费职责。这里的
`app` profile 用于清晰隔离构建与启动范围。TASK-07 起 Java 应用使用两个启动期
固定、仅 DB 0 的具名连接：认证/缓存/限流只注入 `redis-cache`，秒杀装载与 Reservation 只注入
`redis-seckill`；请求期间不创建连接工厂，也不按 dbIndex 选择逻辑库。

TASK-08 的 Redis Stream 消费者位于 `task` 容器。它依赖 MySQL 迁移到 V1.4 和
`redis-seckill` 健康；不依赖 RabbitMQ 完成 Outbox 发布。消费开关默认开启，对账默认 dry-run 且
自动修复关闭。

可观测与测试服务将分别由后续任务加入独立 profile；TASK-03 不提前添加占位容器。

## 3. 服务用途与端口

| 服务 | 宿主机默认端口 | 数据策略 | 持久卷 |
| --- | --- | --- | --- |
| MySQL 8.0 | `4306` | `utf8mb4`、UTC (`+00:00`)、慢查询日志、Performance Schema | `mysql_data` |
| `redis-cache` | `7379` | 仅 DB 0，`allkeys-lfu`，RDB，可重建 | `redis_cache_data` |
| `redis-seckill` | `7380` | 仅 DB 0，`noeviction`，AOF everysec + RDB | `redis_seckill_data` |
| RabbitMQ Management | `6672` / `15673` | 官方 management 镜像；不安装 delayed-message 插件 | `rabbitmq_data` |

MySQL 默认限制为 1 GiB/1.5 CPU，两个 Redis 和 RabbitMQ 也有内存上限；可在 env 文件中覆盖。
MySQL 不再挂载 `/docker-entrypoint-initdb.d` 结构脚本。`database-migrator` 只读挂载生产迁移目录，
在 MySQL 健康后执行一次 Flyway `migrate`；三个应用等待其成功退出。开发/压测数据目录只读挂载到
`/opt/hotshop/data`，不会自动执行。迁移、接管、checksum 与数据命令详见
`docs/architecture/database-schema.md`。

### 3.1 UTC 时间契约与旧数据卷

干净环境统一使用 UTC：

- `.env.example` 和 Compose 默认 `TZ=UTC`；
- MySQL `--default-time-zone=+00:00`，新连接的 global/session time zone 均为 `+00:00`；
- Flyway、portal、admin、task 的 JDBC URL 都使用 `serverTimezone=UTC`，Hikari 同时要求
  Connector/J 把 connection/session time zone 固定为 UTC；
- 三个 Java 进程运行在 UTC；普通订单到期判断最终使用 MySQL `UTC_TIMESTAMP(6)`，不依赖宿主默认时区。

`DATETIME(6)` 本身不保存时区。把 Compose 配置从旧的 `Asia/Shanghai`/`+08:00` 改成 UTC，
**不会自动换算已有 `mysql_data` 卷中的历史值**。旧卷若曾以 `+08:00` 写入，继续使用前必须先备份，
再由数据所有者选择经过核对的一次性历史值转换；纯本地开发数据也可在确认无需保留后，由数据所有者
自行重建本地卷。脚本和任务不会删除、转换或重建现有 `hotshop` 数据卷。

可重复 UTC 验证会创建随机命名的隔离 Compose project/volume，只启动 MySQL，检查
`@@global.time_zone`、`@@session.time_zone` 以及 `NOW(6)` 与 `UTC_TIMESTAMP(6)` 的差值，随后仅删除
该随机隔离资源：

```powershell
.\script\verify-compose-utc.ps1
```

预期输出包含 `global=+00:00 session=+00:00 deltaSeconds=0`。

### 3.2 TASK-08 Stream 消费配置

Compose 将以下配置传入 `task`；生产部署应按吞吐和故障恢复目标显式设置，不要通过扩大批量掩盖
长期 Pending：

```text
HOTSHOP_SECKILL_ORDER_CONSUMER_ENABLED=true
HOTSHOP_SECKILL_ORDER_GROUP=hotshop-order-v1
HOTSHOP_SECKILL_ORDER_CONSUMER_PREFIX=order
HOTSHOP_SECKILL_ORDER_READ_BATCH=20
HOTSHOP_SECKILL_ORDER_READ_BLOCK=2s
HOTSHOP_SECKILL_ORDER_POLL_DELAY=250ms
HOTSHOP_SECKILL_ORDER_DISCOVERY_INTERVAL=10s
HOTSHOP_SECKILL_ORDER_CLAIM_IDLE=30s
HOTSHOP_SECKILL_ORDER_CLAIM_BATCH=20
HOTSHOP_SECKILL_ORDER_RETRY_INITIAL_BACKOFF=1s
HOTSHOP_SECKILL_ORDER_RETRY_MAX_BACKOFF=5m
HOTSHOP_SECKILL_ORDER_RETRY_MULTIPLIER=2.0
HOTSHOP_SECKILL_ORDER_DETERMINISTIC_FAILURE_ATTEMPTS=3
HOTSHOP_SECKILL_ORDER_TIMEOUT=15m
HOTSHOP_SECKILL_PAYMENT_TIMEOUT=15m
HOTSHOP_SECKILL_RECONCILIATION_INTERVAL=5m
HOTSHOP_SECKILL_RECONCILIATION_BATCH=100
HOTSHOP_SECKILL_RECONCILIATION_DRY_RUN=true
HOTSHOP_SECKILL_RECONCILIATION_AUTO_REPAIR=false
```

Consumer name 会在前缀后追加 hostname、PID 和随机后缀，不能把多个副本配置成固定的同名
consumer。只有同时把 dry-run 设为 `false` 且 auto-repair 设为 `true` 才会执行修复白名单；改动这
两个开关前必须先评审 OPEN 对账问题和 dry-run 证据。

## 4. 健康与配置检查

静态解析和运行状态：

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example ps
docker compose --env-file .env.example run --rm database-migrator validate
```

确认两个 Redis 只开放 DB 0，且策略不同：

```powershell
docker compose --env-file .env.example exec -T redis-cache redis-cli CONFIG GET databases maxmemory-policy appendonly save
docker compose --env-file .env.example exec -T redis-seckill redis-cli CONFIG GET databases maxmemory-policy appendonly appendfsync aof-use-rdb-preamble save
```

容器已设置 `REDISCLI_AUTH`，所以以上命令不需要把密码放在命令行。预期两者 `databases` 都是
`1`；cache 为 `allkeys-lfu`/RDB，seckill 为 `noeviction`/AOF + RDB。

TASK-07 活动装载与预约命令见 `docs/architecture/flash-sale-reservation.md`；TASK-08 的
`XINFO GROUPS`、`XPENDING`、处理账本和 dry-run 调查命令见
`docs/architecture/stream-order-processing.md`。`redis-seckill` OOM 时预约返回脱敏 503，不会同步
写 MySQL；不要把 policy 改为淘汰。`redis-cache` 故障不会改变 seckill 已有库存、Reservation 或
Stream，反之亦然。

快速检查 Registry、消费者组和积压（活动 7001）：

```powershell
$stream = 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations'
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  SMEMBERS 'hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams'
docker compose --env-file .env.example exec -T redis-seckill redis-cli XINFO GROUPS $stream
docker compose --env-file .env.example exec -T redis-seckill redis-cli `
  XPENDING $stream hotshop-order-v1 - + 100
```

不能用 `XDEL` 清 Pending，也不能仅因 delivery count 高就恢复库存。先核对
`seckill_event_processing` 和 `seckill_reconciliation_issue`；瞬时依赖故障应保留 Pending 等待
恢复。

确认 RabbitMQ 运行且没有 delayed-message 插件：

```powershell
docker compose --env-file .env.example exec -T rabbitmq rabbitmq-diagnostics -q ping
docker compose --env-file .env.example exec -T rabbitmq rabbitmq-plugins list --enabled --minimal
```

启用列表应只包含官方 management 相关插件，不应出现第三方延迟交换机插件。

## 5. 停止、重启与持久性验证

普通停止与恢复不会删除持久卷：

```powershell
docker compose --env-file .env.example stop
docker compose --env-file .env.example up -d --wait
```

可在停止前分别写入一个临时探针，恢复后读取并清理：

```powershell
docker compose --env-file .env.example exec -T mysql mysql -uroot "-pchange-me-local-mysql" -e "CREATE DATABASE IF NOT EXISTS task03_probe; CREATE TABLE IF NOT EXISTS task03_probe.marker(id INT PRIMARY KEY); INSERT IGNORE INTO task03_probe.marker VALUES (1);"
docker compose --env-file .env.example exec -T redis-cache redis-cli SET task03:persistence:probe cache
docker compose --env-file .env.example exec -T redis-seckill redis-cli SET task03:persistence:probe seckill
docker compose --env-file .env.example exec -T rabbitmq rabbitmqctl add_vhost task03-probe

docker compose --env-file .env.example stop
docker compose --env-file .env.example up -d --wait

docker compose --env-file .env.example exec -T mysql mysql -uroot "-pchange-me-local-mysql" -Nse "SELECT COUNT(*) FROM task03_probe.marker"
docker compose --env-file .env.example exec -T redis-cache redis-cli GET task03:persistence:probe
docker compose --env-file .env.example exec -T redis-seckill redis-cli GET task03:persistence:probe
docker compose --env-file .env.example exec -T rabbitmq rabbitmqctl list_vhosts --silent

docker compose --env-file .env.example exec -T mysql mysql -uroot "-pchange-me-local-mysql" -e "DROP DATABASE task03_probe"
docker compose --env-file .env.example exec -T redis-cache redis-cli DEL task03:persistence:probe
docker compose --env-file .env.example exec -T redis-seckill redis-cli DEL task03:persistence:probe
docker compose --env-file .env.example exec -T rabbitmq rabbitmqctl delete_vhost task03-probe
```

若使用自定义 env 文件，请把示例中的 MySQL 密码替换为对应本机值；不要把真实密码粘贴进提交内容。
`docker compose down` 只删除容器和网络，默认保留命名卷；除非明确要清空本机数据，不要使用
`down --volumes`。

## 6. 常见问题

- 宿主端口冲突：在 env 文件中覆盖 `MYSQL_PORT`、`REDIS_CACHE_PORT`、
  `REDIS_SECKILL_PORT`、`RABBITMQ_AMQP_PORT` 或 `RABBITMQ_MANAGEMENT_PORT`。
- MySQL 首次启动较慢：健康检查有 40 秒启动宽限和 20 次重试；使用 `docker compose logs mysql`
  查看初始化失败原因。若复用旧数据卷后 MySQL 显示 unhealthy，先核对外部 env 中的 root 密码；
  健康检查会实际认证并执行 `SELECT 1`，不会把仅端口存活误判为就绪。
- Redis 写入返回 OOM：`redis-seckill` 的 `noeviction` 是正确性约束，应扩容或停止接入并告警，
  不能改成淘汰业务状态；cache 则会按 LFU 淘汰。
- 旧 `redis` 容器/`redis_data` 卷：TASK-03 将服务拆分为两个新名称，不会自动删除旧容器或旧卷；
  确认不再需要后再由环境所有者手工处理。

## 7. TASK-09 可靠消息运行手册

RabbitMQ wrapper 只继承官方 `rabbitmq:4.0.7-management-alpine`，不下载或启用第三方延迟插件。
固定订单超时由 durable TTL 队列和 DLX 完成。Portal 不配置 RabbitMQ，也不依赖其健康状态；只要
MySQL 可用，普通订单与两条 Outbox 可以提交。Task 才持有 RabbitMQ 连接，并使用 correlated
publisher confirm、mandatory publish 和 publisher returns。

常用诊断：

```powershell
docker compose --env-file .env.example ps
docker compose --env-file .env.example logs task-service rabbitmq
docker compose --env-file .env.example exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

Outbox 自动重试最多 8 次，退避从 1 秒指数增长到最多 5 分钟。租约默认 30 秒；实例退出后不要手工
篡改 `PUBLISHING`，等待租约过期即可由其他实例接管。`FAILED` 排障步骤：先查看 task/RabbitMQ 与
脱敏 failure category，再由 Administrator 调用 `GET /admin/api/v1/outbox/failed`；确认根因消除后，
以明确原因调用 `POST /admin/api/v1/outbox/{eventId}/replay`。接口只把 MySQL 状态改回可领取状态，
不在 HTTP 线程发送消息；重放把本轮连续尝试归零、保留生命周期发布次数、累计人工重放次数并追加
`audit_log`。`FAILED` 不会自动领取；不得重放 `PUBLISHED`，也不得向 User、
匿名或任何 Agent 暴露该能力。

超时链路的队列级期限为 15 分钟，即 900000 ms；发布器同时按 `expiresAtMs - now` 设置剩余消息
expiration，已到期事件直接进入 ready exchange。最终判断始终使用 MySQL `expires_at` 和
`UTC_TIMESTAMP(6)`。普通与秒杀订单都由同一 timeout 状态机处理。秒杀订单额外恢复 Activity stock、
结束 Reservation，并通过 `SECKILL_PAYMENT_EXPIRED` 可靠事件异步释放 Redis User slot 和修复库存投影。
补偿成功会追加 SYSTEM/TASK 的 `INVENTORY_COMPENSATED` 审计；重复事件不会重复补偿或审计。
# Local Mock Payment

该功能不是真实支付。保持默认 `HOTSHOP_MOCK_PAYMENT_ENABLED=false` 时无需 Secret。启用前在仓库外生成至少 32 UTF-8 字节的随机值并设置 `HOTSHOP_MOCK_PAYMENT_SECRET`，同时令 Portal 与 Task 使用同一值。可配置时钟偏差、callback URL、HTTP timeout、retry delay/attempts、最大模拟 delay/duplicate count 和 body bytes；变量名见 `.env.example`。容器内默认回调地址是 `http://portal-service:8080/provider-callbacks/v1/mock-payment`。

检查 `hotshop.mock-payment.callback.dead.v1` 可发现确定性 4xx 或重试耗尽。不得把队列 body、HMAC、nonce 或 Secret 复制到工单和日志。Portal/Broker 恢复后，MySQL NEW/过期 PUBLISHING Outbox 会自动恢复投递。

秒杀 Redis 补偿投递配置：

```text
HOTSHOP_SECKILL_PAYMENT_EXPIRED_RETRY_DELAY=2s
HOTSHOP_SECKILL_PAYMENT_EXPIRED_CONFIRM_TIMEOUT=3s
HOTSHOP_SECKILL_PAYMENT_EXPIRED_MAX_DELIVERY_ATTEMPTS=5
```

正常或 Lua `IDEMPOTENT` 会 ACK；Schema/事实冲突进入 `hotshop.seckill.payment-expired.dead.v1`；Redis 暂时不可用进入 `hotshop.seckill.payment-expired.retry.v1`。只有 retry publish 获得 confirm 后原消息才 ACK。达到上限后进入 DLQ，主队列和 retry queue 应为空。排障时核对 Redis stock、Reservation `PAYMENT_EXPIRED` 与 User slot 已删除三项事实，不要直接修改 MySQL 与 Redis 形成双写。
