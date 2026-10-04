# HotShop 容器环境运行手册

## 本地入口

准备 PowerShell 7+、Docker Linux 容器和 Compose 2.24.4+，在仓库根目录执行：

```powershell
pwsh -NoProfile -File ./script/demo.ps1 -Action Start
pwsh -NoProfile -File ./script/demo.ps1 -Action Status
pwsh -NoProfile -File ./script/demo.ps1 -Action Logs
pwsh -NoProfile -File ./script/demo.ps1 -Action Stop
```

本地只运行一个 `hotshop` Compose 项目，每个子服务使用自己的容器。固定镜像为 `hotshop-{admin,portal,task,agent,web,rabbitmq}:local`，构建替换同一标签。Start 仅构建缺失或构建输入变化的服务；日常启动复用既有镜像。源码指纹与镜像 ID 保存在 `.local/keys/hotshop/build-state.json`。

凭据和认证密钥保存在 `.local/keys/hotshop/`，不读取根 `.env` 或公开占位凭据。脚本临时加载配置，执行后恢复 shell 环境。首次启动生成密钥并等待 Flyway 成功、应用就绪，只有业务数据库为空才写入演示目录、知识索引和活动。已有用户、商品、库存和订单始终保留。

默认提供 FakeModel、deterministic embedding 与本地模拟支付，不需要模型 API Key。首页为 http://127.0.0.1:18080，管理员账号及体验流程见 [演示指南](../delivery/demo.md)。所有服务端口只绑定 loopback；后端端口由 Docker 分配，Status 显示实际绑定。

`app` profile 包含三个 Java 服务，`agent` 包含 Agent 与 Qdrant。观测组件使用同一项目的 `observability` profile，默认不开启。隔离集成验证由 GitHub 托管 CI 执行。

## 凭据与持久卷

复用现有配置和卷，不重复生成密钥或重置数据。`HOTSHOP_DATA_PROJECT` 可以指定已有业务卷的名称前缀，容器仍属于固定的 `hotshop` 项目；默认卷前缀为当前 Compose 项目名。迁移已有数据时，该值与数据库密码必须匹配实际卷。

Stop 不删除资源。清理前逐项核对项目标签、镜像 ID 和容器引用，只移除确认属于 HotShop 的容器、项目镜像和闲置网络，保留业务卷、密钥、备份及其他项目。不要执行全局 prune 或本地 `down -v`。

## 3. 服务用途与端口

本地后端端口由 Docker 分配，使用 `script/demo.ps1 -Action Status` 查询实际 loopback 地址。下表列出容器内部端口。

| 服务 | 容器内部端口 | 数据策略 | 持久卷 |
| --- | --- | --- | --- |
| MySQL 8.4.11 | `3306` | `utf8mb4`、UTC (`+00:00`)、慢查询日志、Performance Schema | `mysql_data` |
| `redis-cache` | `6379` | 仅 DB 0，`allkeys-lfu`，RDB，可重建 | `redis_cache_data` |
| `redis-seckill` | `6379` | 仅 DB 0，`noeviction`，AOF everysec + RDB | `redis_seckill_data` |
| RabbitMQ Management | `5672` / `15672` | 官方 management 镜像；不安装 delayed-message 插件 | `rabbitmq_data` |

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

检查现有数据库的时区：

```powershell
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SELECT @@global.time_zone, @@session.time_zone, UTC_TIMESTAMP()"'
```

预期 global/session time zone 均为 `+00:00`。

### 3.2 Stream 消费配置

Compose 将以下配置传入 `task`；生产部署应按吞吐和故障恢复目标显式设置，不要通过扩大批量掩盖
长期 Pending：

```text
HOTSHOP_SECKILL_ORDER_CONSUMER_ENABLED=true
HOTSHOP_SECKILL_ORDER_GROUP=hotshop-order-v1
HOTSHOP_SECKILL_ORDER_CONSUMER_PREFIX=order
HOTSHOP_SECKILL_ORDER_READ_BATCH=20
HOTSHOP_SECKILL_ORDER_READ_BLOCK=1s
HOTSHOP_REDIS_SECKILL_TIMEOUT=2s
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

启用消费者时，Redis 命令超时必须比阻塞读取时间至少长 1 秒，阻塞读取时间不能小于 1 毫秒。
升级前检查自定义 `.env` 或部署变量：旧的 `READ_BLOCK=2s` / `SECKILL_TIMEOUT=2s` 组合
应改为 `1s / 2s`，或在确实需要较长等待时设为 `2s / 3s`。不满足约束时 Task 启动失败，
错误消息包含配置名和实际时长。空闲返回与 Redis 不可用的区别见[Stream 消费说明](../architecture/stream-order-processing.md)。

## 4. 健康与配置检查

静态解析和运行状态：

```powershell
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent config --quiet
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent ps
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent run --rm database-migrator validate
```

确认两个 Redis 只开放 DB 0，且策略不同：

```powershell
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-cache redis-cli CONFIG GET databases maxmemory-policy appendonly save
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli CONFIG GET databases maxmemory-policy appendonly appendfsync aof-use-rdb-preamble save
```

容器已设置 `REDISCLI_AUTH`，所以以上命令不需要把密码放在命令行。预期两者 `databases` 都是
`1`；cache 为 `allkeys-lfu`/RDB，seckill 为 `noeviction`/AOF + RDB。

活动装载与预约命令见 `docs/architecture/flash-sale-reservation.md`；
`XINFO GROUPS`、`XPENDING`、处理账本和 dry-run 调查命令见
`docs/architecture/stream-order-processing.md`。`redis-seckill` OOM 时预约返回脱敏 503，不会同步
写 MySQL；不要把 policy 改为淘汰。`redis-cache` 故障不会改变 seckill 已有库存、Reservation 或
Stream，反之亦然。

快速检查 Registry、消费者组和积压（活动 7001）：

```powershell
$stream = 'hotshop:seckill:v1:{hotshop-seckill-v1}:activity:7001:reservations'
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli `
  SMEMBERS 'hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams'
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli XINFO GROUPS $stream
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T redis-seckill redis-cli `
  XPENDING $stream hotshop-order-v1 - + 100
```

不能用 `XDEL` 清 Pending，也不能仅因 delivery count 高就恢复库存。先核对
`seckill_event_processing` 和 `seckill_reconciliation_issue`；瞬时依赖故障应保留 Pending 等待
恢复。

确认 RabbitMQ 运行且没有 delayed-message 插件：

```powershell
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T rabbitmq rabbitmq-diagnostics -q ping
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T rabbitmq rabbitmq-plugins list --enabled --minimal
```

启用列表应包含官方 management 和 prometheus 相关插件，不应出现第三方延迟交换机插件。

## 5. 停止、重启与持久性验证

普通停止与恢复不会删除持久卷：

```powershell
pwsh -NoProfile -File ./script/demo.ps1 -Action Stop
pwsh -NoProfile -File ./script/demo.ps1 -Action Start
```

持久性与隔离验证优先运行版本化脚本，并查看对应报告；不要对共享数据卷直接运行初始化或重置命令。

## 6. 常见问题

- 宿主端口冲突：在 env 文件中覆盖 `MYSQL_PORT`、`REDIS_CACHE_PORT`、
  `REDIS_SECKILL_PORT`、`RABBITMQ_AMQP_PORT` 或 `RABBITMQ_MANAGEMENT_PORT`。
- MySQL 首次启动较慢：健康检查有 40 秒启动宽限和 20 次重试；使用 `docker compose logs mysql`
  查看初始化失败原因。若复用旧数据卷后 MySQL 显示 unhealthy，先核对外部 env 中的 root 密码；
  健康检查会实际认证并执行 `SELECT 1`，不会把仅端口存活误判为就绪。
- Redis 写入返回 OOM：`redis-seckill` 的 `noeviction` 是正确性约束，应扩容或停止接入并告警，
  不能改成淘汰业务状态；cache 则会按 LFU 淘汰。
- 旧 `redis` 容器/`redis_data` 卷：当前服务使用 `redis-cache` 和 `redis-seckill` 两个名称，不会自动删除旧容器或旧卷；
  确认不再需要后再由环境所有者手工处理。

## 7. 可靠消息运行手册

RabbitMQ wrapper 只继承官方 `rabbitmq:4.3.6-management-alpine`（基础 digest 以 `docker/rabbitmq/Dockerfile` 为准），不下载或启用第三方延迟插件。
固定订单超时由 durable TTL 队列和 DLX 完成。Portal 不配置 RabbitMQ，也不依赖其健康状态；只要
MySQL 可用，普通订单与两条 Outbox 可以提交。Task 才持有 RabbitMQ 连接，并使用 correlated
publisher confirm、mandatory publish 和 publisher returns。

常用诊断：

```powershell
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent ps
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent logs task-service rabbitmq
docker compose -p hotshop --env-file .local/keys/hotshop/.env.demo -f docker-compose.yml -f docker-compose.demo.yml --profile app --profile agent exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
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

## 8. 本地模拟支付

该功能不是真实支付。保持默认 `HOTSHOP_MOCK_PAYMENT_ENABLED=false` 时无需 Secret。启用前在仓库外生成至少 32 UTF-8 字节的随机值并设置 `HOTSHOP_MOCK_PAYMENT_SECRET`，同时令 Portal 与 Task 使用同一值。可配置时钟偏差、callback URL、HTTP timeout、retry delay/attempts、最大模拟 delay/duplicate count 和 body bytes；变量名见 `.env.example`。容器内默认回调地址是 `http://portal-service:8080/provider-callbacks/v1/mock-payment`。

检查 `hotshop.mock-payment.callback.dead.v1` 可发现确定性 4xx 或重试耗尽。不得把队列 body、HMAC、nonce 或 Secret 复制到工单和日志。Portal/Broker 恢复后，MySQL NEW/过期 PUBLISHING Outbox 会自动恢复投递。

秒杀 Redis 补偿投递配置：

```text
HOTSHOP_SECKILL_PAYMENT_EXPIRED_RETRY_DELAY=2s
HOTSHOP_SECKILL_PAYMENT_EXPIRED_CONFIRM_TIMEOUT=3s
HOTSHOP_SECKILL_PAYMENT_EXPIRED_MAX_DELIVERY_ATTEMPTS=5
```

正常或 Lua `IDEMPOTENT` 会 ACK；Schema/事实冲突进入 `hotshop.seckill.payment-expired.dead.v1`；Redis 暂时不可用进入 `hotshop.seckill.payment-expired.retry.v1`。只有 retry publish 获得 confirm 后原消息才 ACK。达到上限后进入 DLQ，主队列和 retry queue 应为空。排障时核对 Redis stock、Reservation `PAYMENT_EXPIRED` 与 User slot 已删除三项事实，不要直接修改 MySQL 与 Redis 形成双写。
