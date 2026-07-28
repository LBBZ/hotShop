# HotShop 容器环境运行手册

本文档覆盖 TASK-03 的本机数据基础设施及 TASK-02 的数据库迁移。默认 Compose 集合启动 MySQL、
一次性 `database-migrator`、`redis-cache`、`redis-seckill` 和 RabbitMQ；三个 Java 进程位于
`app` profile。

## 1. 前置条件与凭据

- Docker Engine 及 Docker Compose 可用。
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

当前 Java RabbitMQ 配置仍声明 `x-delayed-message` exchange，而 TASK-03 按总纲移除了对应第三方
插件；因此 portal/task 在 TASK-09 改成 TTL 队列 + DLX 前可能无法完成启动。这里的 `app` profile
用于清晰隔离构建与启动范围，不把当前已知的不兼容伪装成通过。应用目前只连接
`redis-cache`；`redis-seckill` 只做基础设施预配，后续任务再接入。

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
- 三个 Java 进程运行在 UTC，`OrderTimeoutJob` 另外使用显式 UTC `Clock`，不依赖宿主默认时区。

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

确认 RabbitMQ 运行且没有 delayed-message 插件：

```powershell
docker compose --env-file .env.example exec -T rabbitmq rabbitmq-diagnostics -q ping
docker compose --env-file .env.example exec -T rabbitmq rabbitmq-plugins list --enabled --minimal
```

启用列表应包含官方 management 相关插件，不应包含 `rabbitmq_delayed_message_exchange`。

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
