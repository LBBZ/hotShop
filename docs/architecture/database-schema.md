# HotShop 数据库迁移与约束设计

> TASK-02 基线与 TASK-06 审计增量；适用于 MySQL 8.0。

## 1. 唯一结构来源与迁移责任

生产结构的唯一事实来源是
`database/src/main/resources/db/migration`。旧的 `docker/mysql/init.sql` 已移除，MySQL 容器不再
挂载 `/docker-entrypoint-initdb.d` 结构脚本。`database/data` 只包含显式执行的数据文件，不是
Flyway location，也不会随容器启动自动执行。

三个长期运行的 Java 进程都通过 `common-db.yml` 显式设置 `spring.flyway.enabled=false`。
Compose 中只有一次性 `database-migrator` 可以执行迁移；portal、admin、task 必须等待它以退出码 0
结束。这样迁移有单一所有者，应用副本数量不会改变结构初始化行为。

迁移规则：

- 文件名必须为 `V<major>_<minor>__<lower_snake_case_description>.sql`，例如
  `V1_1__take_over_legacy_tables.sql`；下划线版本在 Flyway 中显示为 `1.1`。
- 已应用的版本文件不可编辑、重命名或重排；结构变化只能追加更高版本。
- `validateMigrationNaming=true`、`outOfOrder=false`、`cleanDisabled=true`、
  `baselineOnMigrate=false`。缺失位置、非法命名、缺失迁移或 checksum 不一致均使部署失败。
- `repair` 不是日常接管手段。只有确认历史表记录本身损坏、完成备份和变更评审后才可使用。
- 版本迁移不包含开发或压测数据。

当前版本：

| Flyway 版本 | 文件 | 作用 |
| --- | --- | --- |
| `1.0` | `V1_0__create_hotshop_schema.sql` | 创建 11 张业务/平台表、约束和索引 |
| `1.1` | `V1_1__take_over_legacy_tables.sql` | 条件导入旧四表，清理旧裸表及旧维护过程 |
| `1.2` | `V1_2__secure_refresh_sessions.sql` | 为分域 Refresh Session 增加 session type、CSRF hash、family 索引和 SERVICE 审计 actor |
| `1.3` | `V1_3__unified_append_only_audit_log.sql` | 增加 delegated actor、source、调查索引与 UPDATE/DELETE 阻断触发器 |

## 2. 表、业务键与状态

所有表都使用 InnoDB、`utf8mb4_0900_ai_ci` 和微秒时间。结构不声明数据库引用约束或级联动作；
跨表 ID 是应用层引用。金额统一为 `DECIMAL(19,2)`，没有浮点金额。

| 表 | 用途与业务键 | 关键约束/状态 |
| --- | --- | --- |
| `app_user` | User；Username 与非空 Email 均为全生命周期唯一业务键，软删除后也不释放 | role=`ROLE_USER/ROLE_ADMIN`；status=`ACTIVE/LOCKED/DISABLED`；逻辑删除、version |
| `catalog_product` | Catalog Product；`sku` 唯一 | price≥0、stock≥0；`DRAFT/ACTIVE/INACTIVE`；逻辑删除、version |
| `flash_sale_activity` | Flash Sale Activity；`activity_code` 唯一 | 售价/库存非负，可售库存不超过总库存，每人限购>0，结束晚于开始；受限状态、version |
| `sale_reservation` | Reservation；`reservation_no` 唯一 | quantity>0、金额≥0；受限状态、version |
| `sales_order` | Order；`order_id` 业务订单号主键；每个非空 Reservation 最多一个 Order | 金额≥0、三字母币种；`PENDING/PAID/SHIPPED/COMPLETED/CANCELED`；version |
| `sales_order_item` | Order Item；同一 Order 的 Catalog Product 唯一 | quantity>0、单价/行金额≥0、行金额=单价×数量 |
| `payment_order` | Payment Order；`payment_no` 唯一；order+provider 唯一；provider transaction 非空时唯一 | 金额≥0；`PENDING/SUCCEEDED/FAILED/CLOSED`；version |
| `refresh_token` | Refresh Session；应用生成 BIGINT 主键；只保存 Refresh/CSRF 的 SHA-256 hash；token hash 唯一，family 用于轮换/泄露处理 | `session_type=USER/ADMIN`；每个非空 `parent_token_id` 最多一个后继且不能指向自身；状态 `ACTIVE/ROTATED/REVOKED/EXPIRED/REUSED`；无外键；过期晚于创建 |
| `outbox_event` | 事务事件；`event_id` 唯一 | JSON payload；`NEW/PUBLISHING/PUBLISHED/FAILED`；attempts≥0 |
| `processed_event` | 消费去重 | `(consumer_name,event_id)` 主键，同一事件可被不同消费者各处理一次 |
| `audit_log` | 只追加统一审计；`audit_id` 为稳定游标 | actor/delegated actor、action、resource、result、request/trace、source、微秒发生时间和脱敏 JSON 摘要；触发器拒绝 UPDATE/DELETE |

预约的 `effective_slot` 是生成列：`RESERVED`、`ORDER_CREATED`、`COMPENSATING` 映射为 1，
其他状态映射为 NULL；唯一键 `(activity_id,user_id,effective_slot)` 保证活动内同一用户最多一个有效
预约，同时允许补偿完成后重新预约。

Username 和 Email 的唯一键覆盖已软删除 User。`existsByUsername`、`existsByEmail` 用于注册可用性判断，
因此查询全部记录；登录和普通 User 查询继续限定 `deleted_at IS NULL`。这保证应用预检与数据库最终
约束一致，也落实 `CONTEXT.md` 中 Username 永不重新分配的语义。

Refresh Token 是至少 256-bit CSPRNG 生成的 opaque 值，不是 JWT；明文只存在于一次 HTTP cookie
响应与调用方内存中。`token_hash` 和 `csrf_hash` 使用 SHA-256，不保存或记录明文。User 与
Administrator 由 `session_type`、独立 issuer/audience 和独立 family 隔离。V1.2 会把升级前无法满足
新 CSRF 契约的遗留 ACTIVE 记录置为 REVOKED，调用方必须重新登录。

轮换通过 `UNIQUE(parent_token_id)` 保证两个并发请求不能为同一父令牌创建第二个后继；
MySQL 唯一键允许多个 NULL，因此根令牌不受影响。`CHECK(parent_token_id IS NULL OR
parent_token_id <> refresh_token_id)` 拒绝自引用。MySQL 不允许 CHECK 引用自增列，因此
`refresh_token_id` 是由应用生成的 BIGINT，而不是 AUTO_INCREMENT。由于不使用外键，后续轮换用例
在单个事务中以 `SELECT ... FOR UPDATE` 锁定当前 hash，校验其 User、family、session type、
issuer/audience、到期时间和状态。成功时先将当前记录置为 ROTATED，再插入唯一 successor。再次使用
ROTATED token 会将其置为 REUSED、撤销同 family 的所有 ACTIVE token，并在同一事务追加脱敏
`REFRESH_TOKEN_REUSE_DETECTED` 审计事件。并发 loser 也遵循 reuse 语义，因此最终 family 被撤销，
而不会产生两条活动链。

## 3. 索引依据

索引列顺序按等值条件、范围/排序列、稳定游标列排列，不为“看起来常用”而单独建索引。

| 表 / 索引前缀 | 查询场景与列顺序 |
| --- | --- |
| `app_user(role,status,created_at)` | 管理端按角色和状态筛选，再按创建时间浏览 |
| `app_user(status,created_at)` | 账户生命周期任务按状态和创建时间扫描 |
| `catalog_product(status,category,product_id)` | 前台有效商品分类列表，以主键稳定翻页 |
| `catalog_product(category,price,product_id)` | 分类内价格区间筛选，以主键稳定翻页 |
| `catalog_product(status,created_at)` | 后台按上架状态和创建时间浏览 |
| `flash_sale_activity(product_id,status,starts_at,ends_at)` | 查询商品在时间窗口内的指定状态活动 |
| `flash_sale_activity(status,starts_at,ends_at)` | 活动调度器按状态和窗口扫描 |
| `sale_reservation(user_id,created_at)` | 用户预约历史 |
| `sale_reservation(activity_id,status,created_at)` | 活动维度状态统计/对账 |
| `sale_reservation(status,expires_at)` | 过期或补偿任务按状态、到期时间扫描 |
| `sales_order(user_id,created_at,order_id)` | 用户订单倒序列表与稳定游标 |
| `sales_order(status,created_at,order_id)` | 后台状态列表/对账 |
| `sales_order(status,expires_at,order_id)` | 未支付订单到期扫描 |
| `sales_order_item(product_id,order_id)` | 商品维度追溯订单 |
| `payment_order(status,expires_at,payment_id)` | 待支付单过期扫描 |
| `payment_order(status,created_at,payment_id)` | 支付状态列表与对账 |
| `refresh_token(family_id,created_at)` | 令牌复用后撤销整个 family |
| `refresh_token(user_id,status,expires_at)` | 用户有效 token 查询和过期清理 |
| `refresh_token(parent_token_id)`（唯一） | 由父令牌定位唯一后继，并仲裁并发轮换 |
| `outbox_event(status,available_at,outbox_id)` | 发布器领取可投递事件，稳定批量翻页 |
| `outbox_event(aggregate_type,aggregate_id,outbox_id)` | 聚合事件追溯 |
| `processed_event(event_id)` | 跨消费者调查同一事件 |
| `audit_log` 的 occurred/actor/resource/request 索引 | 按时间、操作者、资源或 request ID 调查 |

V1.3 将 actor、delegated actor、action、resource、result 和 source 的调查索引统一扩展为
`occurred_at,audit_id` 稳定尾部；另外保留 request ID 并增加 trace ID 索引，用于从 HTTP/trace
关联到审计事实。查询固定按 `occurred_at DESC,audit_id DESC`，相同微秒也不会乱序。

`audit_log_prevent_update` 和 `audit_log_prevent_delete` 在数据库层以 SQLSTATE `45000` 拒绝任何
行更新或删除，包括绕过业务 API 的普通 SQL。部署迁移由 Compose 中的 MySQL root 迁移身份执行；
启用 binary log 且使用非特权迁移用户的隔离环境，必须由数据库管理员预先允许受控创建 trigger。
应用运行期只执行 INSERT/SELECT，不提供任何修改或清空审计数据的业务能力。

`LIKE '%keyword%'` 不能有效使用普通 B-tree；TASK-02 不提前引入全文检索。后续搜索设计需按数据规模
选择前缀查询、专用搜索或受控降级，不能误称当前索引覆盖任意子串。

## 4. 无数据库引用约束时的一致性策略

数据库允许写入缺少父记录的 ID，这是有意边界，不代表应用可以跳过校验。每个后续写用例必须：

1. 在同一个本地事务中读取所需父记录，检查存在、未逻辑删除且状态允许；
2. 使用受影响行数或条件更新再次约束会变化的状态/库存/version；
3. 父实体采用逻辑删除，避免通过物理删除制造新的悬空引用；
4. 依靠预约、订单、支付和消息的业务唯一键解决并发重复；
5. 校验失败返回领域错误，不把底层 ID 静默接受为有效引用。

`SchemaConstraintTest.applicationReferenceGuardRejectsMissingParentBeforeWrite` 同时证明两点：数据库本身会
接受未校验的悬空订单，而应用式 active-record guard 会在写入前拒绝缺失父记录。TASK-02 只固定策略与
证据；后续业务任务必须在各自命令服务中实际调用相应校验。

## 5. 空库、重复启动与校验

首次启动：

```powershell
docker compose --env-file .env.example up -d --wait
docker compose --env-file .env.example wait database-migrator
```

第一条命令启动服务；第二条命令将 migrator 的真实退出码传播给调用方。不能只依据
`up --wait` 判断一次性容器已经迁移成功。`database-migrator` 在 MySQL 健康后执行 `migrate`；
app profile 也通过 `service_completed_successfully` 阻止三个 Java 进程在迁移失败后启动。
同一数据库再次启动或显式执行：

```powershell
docker compose --env-file .env.example run --rm database-migrator migrate
```

应显示 schema 已是最新，新增迁移数为 0。checksum 与历史校验：

```powershell
docker compose --env-file .env.example run --rm database-migrator validate
docker compose --env-file .env.example run --rm database-migrator info
```

若 validate 失败，不得编辑历史表、手改结构或用 `repair` 掩盖原因；应恢复版本文件或追加纠正迁移。

## 6. 已有本地库接管

不要删除或重建现有 `mysql_data` 卷。接管前停止应用写入并对该卷/数据库做可恢复备份，然后确认：

- 没有 `flyway_schema_history`；
- 旧结构确实是 `user/product/order/order_item` 四表，目标新表尚不存在；
- username/email 无重复，角色和订单状态在新枚举内；
- 金额、库存非负，订单项数量大于 0；
- 没有仓库未知表引用这四张旧表。

默认 `baselineOnMigrate=false` 会让非空未知 schema 的普通 `migrate` 失败，防止误接管。确认预检后显式：

```powershell
docker compose --env-file .env.example run --rm database-migrator -baselineVersion=0 baseline
docker compose --env-file .env.example run --rm database-migrator migrate
docker compose --env-file .env.example run --rm database-migrator validate
```

版本 `1.0` 创建新结构，`1.1` 复制旧数据并按依赖顺序移除旧四表和旧
`delete_and_reset` 过程。任何约束不满足都会使迁移失败；应从备份恢复到隔离环境清理数据后重试，
不能在正式库上临时放宽迁移。`LegacyTakeoverTest` 使用隔离 MySQL 自动验证这条路径。

## 7. 开发与压测数据

数据文件只读挂载到 MySQL 的 `/opt/hotshop/data`，从不自动执行。

确定性开发数据：

```powershell
docker compose --env-file .env.example exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --database="$MYSQL_DATABASE" < /opt/hotshop/data/dev-data.sql'
```

确定性压测数据默认 seed=42、10,000 用户、1,000 商品：

```powershell
docker compose --env-file .env.example exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --database="$MYSQL_DATABASE" < /opt/hotshop/data/load-data.sql'
```

需要其他规模时，把 `load-data.sql` 复制到仓库外并只调整开头的 seed/count 默认值，再显式输入 MySQL；
脚本内置最大值 100,000。两份脚本以稳定业务键幂等更新自己的命名空间。不要在生产环境执行，也不要
把性能数据规模写进迁移。

## 8. TASK-02 实际验证

2026-07-26 使用独立 Compose project `hotshop-task02-verify3` 和独立 `mysql_data` 卷验证：

- Flyway 11.20.3 对空 MySQL 8.0.46 应用 `1.0`、`1.1`，退出码 0；
- 同库第二次 `migrate` 输出 `Schema hotShop is up to date. No migration necessary.`；
- `validate` 成功校验 2 个版本迁移；
- `information_schema.referential_constraints` 对当前 schema 计数为 0；
- 历史记录为 `1.0/1`、`1.1/1`；
- 开发数据重复执行后仍为 2 个命名用户；压测数据重复执行后仍为 10,000 用户、1,000 商品；
- `./mvnw -B clean verify` 的 9 个 reactor 模块成功，31 tests、0 failures、0 errors、0 skipped，
  其中数据库模块 19 tests；
- `docker compose --env-file .env.example config --quiet` 与 `git diff --check` 退出码均为 0。

TASK-02-RECONCILE-01 另外以 Testcontainers/MySQL 8.0.46 验证了：软删除 User 的 Username/Email
仍被 exists Mapper 识别且不可由普通查询读出，重复注册被全局唯一键拒绝；两个并发 Refresh Token
轮换只有一个后继写入成功，自引用被 CHECK 拒绝；元数据查询仍为 0 个 FOREIGN KEY。所有容器均由
Testcontainers 隔离和回收。

验收没有停止、修改或删除原有 HotShop 容器/卷。隔离 project 的容器和网络在验证后移除；遵守任务
约束，验证过程中创建的命名卷未删除。
