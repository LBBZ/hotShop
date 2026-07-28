# HotShop 并行执行规则

## 1. 共享工作区规则

多个 Codex 对话看到的是同一个工作区，并不是相互隔离的分支。并行执行必须满足：

1. 每个对话只编辑全局验收对话分配给它的文件。
2. `git status` 中会出现其他对话的改动；不得将这些改动当成自己的，也不得还原、删除、格式化或暂存它们。
3. 禁止使用 `git reset`、`git checkout --`、`git clean`、`git stash` 及批量回滚。
4. 禁止 commit、push、切换分支和创建 PR。
5. 不运行会改写全仓库文件的 formatter、升级器或代码生成器。
6. 必须在交接报告中列出“本任务实际编辑文件”，不能直接用全部 `git status` 冒充自己的改动。
7. 如果必须编辑另一个任务拥有的文件，立即停止并报告，不要抢占文件。
8. 构建或测试可能读到另一个对话尚未完成的文件。若因此失败，记录证据；不能修改对方文件来绕过。

## 2. 依赖波次

以下是当前规划。只有全局验收对话明确宣布“发放”的波次才能开始。

| 波次 | 可并行任务 | 前置条件 |
|---|---|---|
| W0 | TASK-00 | 无 |
| W1 | TASK-01、TASK-03 | TASK-00 通过 |
| W2 | TASK-02 | TASK-01、TASK-03 通过 |
| W3 | TASK-04 | TASK-02 通过 |
| W4 | TASK-05 | TASK-04 通过 |
| W5 | TASK-06、TASK-12、TASK-15 | TASK-05 通过 |
| W6 | TASK-07 | TASK-02、TASK-04、TASK-05 通过 |
| W7 | TASK-08 | TASK-07 通过 |
| W8 | TASK-09 | TASK-08 通过 |
| W9 | TASK-10 | TASK-09 通过 |
| W10 | TASK-11、TASK-13 | TASK-10、TASK-12 通过 |
| W11 | TASK-14、TASK-16 | TASK-06、TASK-10、TASK-11、TASK-13、TASK-15 通过 |
| W12 | TASK-17 | TASK-16 通过 |
| W13 | TASK-18 | TASK-11、TASK-14、TASK-17 通过 |
| W14 | TASK-19 | TASK-18 通过 |
| W15 | TASK-20 | TASK-19 通过 |
| W16 | TASK-21 | TASK-20 通过 |

W5、W10、W11 的具体文件所有权要在发放时重新确认；上表不是提前开工许可。

## 3. 已完成波次：W1

发放时间：2026-07-26。完成验收：2026-07-26。

- TASK-01：通过；
- TASK-03：通过；
- W1-RECONCILE-01：通过。Docker builder 已改用仓库 Maven Wrapper，三个应用镜像均可构建，
  `.env`/`.env.*` 已被 Git 忽略且 `.env.example` 保留。

### 3.1 TASK-01 文件所有权

允许编辑：

- 根 `pom.xml`；
- 各 Maven 模块的 `pom.xml`；
- `.mvn/**`、`mvnw`、`mvnw.cmd`；
- `.gitattributes`；
- 为测试分层所需的 Maven 配置和现有测试注解/命名；
- `docs/quality/build-baseline.md`。

只读，禁止编辑：

- `Dockerfile`、`docker-compose.yml`、`.dockerignore`、`docker/**`；
- Java 生产代码和 `application*.yml`；
- `docs/architecture/current-state.md`、`docs/quality/baseline.md`；
- 路线文档。

TASK-01 不更新 README，避免共享文档冲突。README 统一在后续文档任务处理。

### 3.2 TASK-03 文件所有权

允许编辑：

- `Dockerfile`、`docker-compose.yml`、`.dockerignore`；
- `docker/**`，但 `docker/mysql/init.sql` 只读；
- `.env.example`；
- `docs/runbooks/container-environment.md`。

只读，禁止编辑：

- 根和所有模块的 `pom.xml`；
- `.mvn/**`、`mvnw`、`mvnw.cmd`、`.gitattributes`；
- 所有 Java 源码、Java 测试和 `application*.yml`；
- `docker/mysql/init.sql`；
- 路线文档。

TASK-03 可以让当前应用暂时继续使用 `redis-cache`，并只预配 `redis-seckill`；不得为了接入第二个 Redis
越界修改 Java 配置。Flyway 尚未落地，因此本任务不得删除当前初始化 SQL。

## 4. W1 可复制提示

### TASK-01 新对话提示

```text
你是 HotShop 的 TASK-01 实现工程师，与 TASK-03 在同一个共享工作区并行工作。

先完整阅读：
1. docs/roadmap/MASTER_PLAN.md
2. docs/roadmap/TASK_CATALOG.md 的 TASK-01
3. docs/roadmap/PARALLEL_EXECUTION.md，特别是 W1 与 TASK-01 文件所有权
4. docs/architecture/current-state.md
5. docs/quality/baseline.md

只执行 TASK-01。只能编辑 PARALLEL_EXECUTION.md 分配给 TASK-01 的文件，不得编辑 Dockerfile、
docker-compose.yml、.dockerignore、docker/**、Java 生产代码、application*.yml 或路线文档。
git status 中会出现 TASK-03 和用户的改动，不得还原、删除、格式化、暂存或提交它们。

要求：
- Java 21、当前稳定的 Spring Boot 3.5.x、MyBatis 3.0.x；
- 补全 Maven Wrapper，统一 UTF-8 和构建插件；
- 把外部依赖测试与默认单元测试正确分层，不得靠 skipTests 制造绿色；
- 解决或明确规避 TASK-00 记录的 Maven 依赖解析/可复现构建问题；
- 不改变交易业务语义；
- 将真实构建命令与结果写入 docs/quality/build-baseline.md。

不要 commit、push、切换分支或创建 PR。完成后按 MASTER_PLAN.md 的交接模板报告，并额外列出
“本任务实际编辑文件”；不要把其他对话的文件算进来。
```

### TASK-03 新对话提示

```text
你是 HotShop 的 TASK-03 实现工程师，与 TASK-01 在同一个共享工作区并行工作。

先完整阅读：
1. docs/roadmap/MASTER_PLAN.md
2. docs/roadmap/TASK_CATALOG.md 的 TASK-03
3. docs/roadmap/PARALLEL_EXECUTION.md，特别是 W1 与 TASK-03 文件所有权
4. docs/architecture/current-state.md
5. docs/quality/baseline.md

只执行 TASK-03。只能编辑 PARALLEL_EXECUTION.md 分配给 TASK-03 的文件。不得编辑任何 pom.xml、
.mvn/**、mvnw*、.gitattributes、Java 源码/测试、application*.yml、docker/mysql/init.sql 或路线文档。
git status 中会出现 TASK-01 和用户的改动，不得还原、删除、格式化、暂存或提交它们。

要求：
- 在理解并保留用户现有 Docker 改动的基础上继续演进；
- 拆分 redis-cache 与 redis-seckill，均只用 DB 0；
- redis-seckill 使用 noeviction、AOF + RDB 和独立持久卷；
- RabbitMQ 不安装 delayed-message 插件；
- MySQL、两个 Redis、RabbitMQ 都有可靠健康检查和持久化；
- 使用 profile 或等价方式区分基础设施与完整应用启动；
- 提供 .env.example 和 docs/runbooks/container-environment.md；
- 当前应用可以暂时只连接 redis-cache，不越界修改 Java；
- 不删除 init.sql，不提前实现 Flyway；
- 实际验证 docker compose config、基础设施启动、健康状态和重启持久性。

不要 commit、push、切换分支或创建 PR。完成后按 MASTER_PLAN.md 的交接模板报告，并额外列出
“本任务实际编辑文件”；不要把其他对话的文件算进来。
```

## 5. 已完成波次：W2

发放时间：2026-07-26。W2 只包含 TASK-02，不与其他实现任务并行。

### TASK-02 新对话提示

```text
你是 HotShop 的 TASK-02 实现工程师。当前 W1 已通过验收，本波次只有你修改工作区。

先完整阅读：
1. docs/roadmap/MASTER_PLAN.md
2. docs/roadmap/TASK_CATALOG.md 的 TASK-02
3. docs/roadmap/PARALLEL_EXECUTION.md
4. docs/architecture/current-state.md
5. docs/quality/build-baseline.md
6. docs/runbooks/container-environment.md

只执行 TASK-02，不提前实现 API、鉴权、秒杀、Outbox 发布器、支付或审计业务。保护当前所有未提交改动；
不要 reset、clean、stash、commit、push、切换分支或创建 PR。

硬性要求：
- Flyway 成为数据库结构唯一事实来源，定义清晰的版本命名和校验规则；
- 任何数据库表都不使用外键或级联规则；
- 使用主键、唯一键、NOT NULL、CHECK、精确数据类型和有查询依据的索引维护一致性；
- 金额使用 DECIMAL，数量必须大于 0，库存不得小于 0，状态值必须受约束；
- 避免继续使用 user、order 等易冲突的裸表名；如果重命名，必须同步修复现有 Mapper 并用测试证明兼容；
- 为用户、商品、活动、预约、订单、订单项、支付单、Refresh Token、Outbox、processed_event、
  审计日志建立可演进的结构，但本任务只创建结构和最小映射兼容，不提前实现后续业务；
- 明确唯一业务键，例如用户名/邮箱、活动内一人一份有效预约、支付业务单号、事件 ID 与消费者去重键；
- 处理当前 docker/mysql/init.sql 与 Compose 挂载，使结构不再有第二套来源；
- 明确三个 Java 进程共享数据库时由谁执行迁移，不能靠三个进程无约束地争抢初始化；
- 明确空库迁移、重复启动、checksum 校验和已有本地库接管方式；
- 不得擅自删除现有 Docker 数据卷。验收测试使用隔离数据库、独立 Compose project 或 Testcontainers；
- 提供确定性的开发数据和独立压测数据生成入口，测试数据不得混入生产迁移；
- 使用 Testcontainers/Flyway 编写约束测试，至少覆盖重复业务键、非法状态、负金额、零/负数量、
  负库存，以及无外键条件下的应用层引用校验策略；
- 更新受影响的配置和数据库文档。

结束前实际验证：
- ./mvnw -B clean verify
- Flyway 对空 MySQL 迁移到最新版本
- 对同一数据库重复 migrate 无新增变化
- Flyway validate 成功
- 数据库元数据查询确认不存在 FOREIGN KEY
- 所有约束测试通过
- docker compose --env-file .env.example config --quiet
- git diff --check

无法执行的命令必须给出原始错误，不能用 skipTests 或手工改库制造通过。
完成后按 MASTER_PLAN.md 的统一交接模板报告，并列出实际编辑文件、迁移版本、测试数量和遗留风险。
```

### TASK-02-RECONCILE-01 收口提示

```text
TASK-02 当前为有条件通过。执行 TASK-02-RECONCILE-01，只修复验收发现的领域一致性缺口，
不要开始 TASK-04 或其他后续功能。

开始前阅读：
1. CONTEXT.md
2. docs/roadmap/MASTER_PLAN.md
3. docs/architecture/database-schema.md

必须完成：

1. 移除商品删除后的自增重置路径：
   - 删除 StoredProcedure Java 接口及 XML Mapper；
   - 从 AdminProductController 删除注入和 resetAutoIncrement 调用；
   - 更新接口描述，不再声称删除会重置自增 ID；
   - 更新 MapperCompatibilityTest；
   - 保留商品软删除，不用其他 DDL 或存储过程替代。
   原因：对每次删除执行 ALTER TABLE 会产生元数据锁和隐式提交，软删除场景也不会真正复用 ID。

2. 固定 Username/Email 不复用语义：
   - CONTEXT.md 已定义 Username 永不重新分配；
   - UserMapper 的 existsByUsername/existsByEmail 必须包含已软删除用户，避免应用先判断“可用”后被
     数据库全局唯一键拒绝；
   - 登录和普通查询仍不得返回已删除用户；
   - 增加 Mapper 测试：软删除后 exists 仍为 true、查询不可见、相同用户名/邮箱不能重新注册。

3. 加固 Refresh Token 轮换结构：
   - 一个非空 parent_token_id 最多只能被一个后继令牌使用；
   - 增加唯一约束和必要的自引用检查，但不增加外键；
   - 增加真实 MySQL 约束测试，证明并发产生第二个后继会被拒绝。
   TASK-02 尚未最终验收，当前迁移只应用在隔离验证库，可以在提交前修正 V1_0；不得修改用户原有
   HotShop 数据卷。

4. database 模块对 domain 的依赖只用于 MapperCompatibilityTest，应改为 test scope，避免迁移制品
   在运行时反向依赖业务领域模块。

5. 同步更新 docs/architecture/database-schema.md，确保术语与 CONTEXT.md 一致。

验证：
- 使用 Java 21 和仓库 Maven Wrapper 执行 ./mvnw -B clean verify；
- 数据库模块所有 Testcontainers 测试通过且没有 skipped；
- docker compose --env-file .env.example config --quiet；
- 数据库元数据仍确认 0 个 FOREIGN KEY；
- 全仓库不再存在 StoredProcedure/resetAutoIncrement/delete_and_reset 的生产调用或定义
  （旧迁移接管中用于删除遗留过程的 DROP PROCEDURE 可以保留）；
- git diff --check。

不要 reset、clean、stash、commit、push、切换分支或创建 PR。完成后报告实际编辑文件、测试数量、
命令结果和遗留风险。
```

## 6. 已完成波次：W3

TASK-02 与 TASK-02-RECONCILE-01 已于 2026-07-26 通过验收：

- 9 个 Maven reactor 模块构建成功；
- 31 tests、0 failures、0 errors、0 skipped；
- 空库迁移、重复 migrate、旧库接管和 MyBatis 兼容通过；
- Username/Email 永久占用语义与 `CONTEXT.md` 一致；
- Refresh Token 并发轮换只允许一个后继；
- 生产代码不再包含删除时重置自增的 DDL/存储过程；
- 迁移元数据确认 0 个数据库外键；
- Compose 配置与 `git diff --check` 通过。

W2 已关闭。用户于 2026-07-26 取消全部 grilling 门禁，当前直接进入 W3/TASK-04。

### TASK-04 新对话提示

```text
你是 HotShop 的 TASK-04 实现工程师。当前 W0、W1、W2 均已通过验收，本波次只有你修改工作区。

开始前完整阅读：
1. CONTEXT.md
2. docs/roadmap/MASTER_PLAN.md
3. docs/roadmap/TASK_CATALOG.md 的 TASK-04
4. docs/architecture/database-schema.md
5. 当前 portal/admin Controller、SecurityConfig、GlobalExceptionHandler 和 OpenApiConfig

只执行 TASK-04：建立 API 契约与错误规范。不要提前重写鉴权、秒杀、可靠消息、支付、前端或 Agent。
保护全部未提交改动；不要 reset、clean、stash、commit、push、切换分支或创建 PR。

硬性要求：
- 将正式接口划分为 /api/v1、/admin/api/v1、/agent/api/v1；本任务实现现有 portal/admin 能力，
  Agent 分组只定义边界，不创建伪造业务接口；
- Controller 只能接收/返回 DTO，不能把持久化实体直接暴露给 HTTP；
- 使用 Spring ProblemDetail/RFC 9457 语义统一错误，Content-Type 为 application/problem+json，
  至少包含稳定业务 code、status、detail、instance、requestId、traceId；参数错误提供结构化 violations；
- 生产错误不得泄露堆栈、SQL、表名、内部类名、Token 或密码；
- 建立统一 Request ID：接受合法客户端 ID或服务端生成，并在响应头、日志上下文和错误体中一致返回；
  Trace ID 与 Request ID 含义分开，为后续 Tempo 预留传播边界；
- 明确 JSON 时间、金额、枚举、空值和 ID 格式，金额继续使用 BigDecimal，不转 float/double；
- 商品和订单列表采用稳定排序与游标分页契约；不能把现有 PageHelper offset 分页伪装成游标分页；
- 为未来写接口定义 Idempotency-Key 的格式、冲突和重放语义，但不提前伪造尚未实现的持久化能力；
- 按 public/user/admin 分组生成稳定 OpenAPI JSON；
- 提供可重复生成 TypeScript API client 的脚本和输出边界，生成文件不得手工修改；
- 建立契约兼容检查基线，至少能发现删除路径、删除字段、改变类型或收紧必填项；
- 使用 MockMvc/真实 Spring Security 测试覆盖匿名成功、认证缺失、权限不足、参数错误、资源不存在、
  冲突、限流错误映射和未知异常；
- 旧 /portal/**、/admin/** 路径若移除，必须通过契约测试和文档明确是一次性版本升级，不能留下两套
  无维护计划的正式 API；
- 更新 API 规范文档和真实验证记录。

验收命令至少包括：
- 使用 Java 21、Docker socket 和仓库 Maven Wrapper执行 ./mvnw -B clean verify；
- 生成 OpenAPI 与 TypeScript client；
- 运行契约兼容检查；
- docker compose --env-file .env.example config --quiet；
- git diff --check。

无法完成的验证必须保留原始错误，不得 skip tests 或用静态手写 OpenAPI 冒充运行时契约。
结束时按 MASTER_PLAN.md 的交接模板报告实际编辑文件、接口变更、测试数量、命令结果和遗留风险。
```

### TASK-04 首轮验收结论：待收口（历史记录）

2026-07-27 独立验收确认 TASK-04 主体实现有效，但发现 3 个必须在进入 TASK-05 前修复的问题：

1. 真实 Portal 进程中，`GET /api/v1/auth/login` 被错误映射为 500，而不是 405；
   `Content-Type: text/plain` 调用注册接口被错误映射为 500，而不是 415。两者虽然返回了脱敏
   Problem Details，但把客户端协议错误误报成服务端故障，会污染错误率、告警和 SLO。
2. API 文档声明数据库 `DATETIME(6)` 按 UTC 解释，DTO 映射也把 `LocalDateTime` 直接按 UTC
   转成 `Instant`；但 Compose 当前仍使用 `TZ=Asia/Shanghai`、MySQL `+08:00` 和
   `serverTimezone=Asia/Shanghai`。干净 Compose 环境中的 `CURRENT_TIMESTAMP` 因而会被 API
   错当成 UTC，响应和时间筛选存在 8 小时偏移风险。
3. JSON 中的 BIGINT ID 已生成 TypeScript `string`，但 path/query 中的 `productId`、`userId`
   仍生成 TypeScript `number`。当 ID 超过 JavaScript 的安全整数范围时，前端会在拼 URL 前丢失精度。

已通过且收口任务不得破坏的证据：

- `./mvnw -B clean verify`：9 个 reactor 模块成功，50 tests，0 failures、0 errors、0 skipped；
- 运行时 public/user/admin OpenAPI 抓取成功，三份 JSON 与基线 SHA-256 一致；
- OpenAPI 兼容脚本及 4 个变异自测通过；
- OpenAPI Generator 7.14.0 成功生成三组 `typescript-fetch` client；
- `docker compose --env-file .env.example config --quiet` 与 `git diff --check` 通过；
- DTO 边界、Request ID/Trace ID、Problem Details、稳定 Order keyset 分页和旧路径移除均已验证。

### TASK-04-RECONCILE-01 新对话提示

```text
你是 HotShop 的 TASK-04-RECONCILE-01 收口工程师。TASK-04 主体已经完成，但尚未通过全局验收。
只修复本提示列出的 3 个契约问题，不要开始 TASK-05，不要重写现有鉴权、交易、消息或分页架构。

开始前完整阅读：
1. CONTEXT.md
2. docs/roadmap/MASTER_PLAN.md
3. docs/roadmap/TASK_CATALOG.md 的 TASK-04
4. docs/roadmap/PARALLEL_EXECUTION.md 的 TASK-04 验收结论
5. docs/api/api-contract.md
6. docs/runbooks/container-environment.md
7. GlobalExceptionHandler、OpenApiConfig、ApiDtoMapper、portal/admin Controller 与 Compose 时区配置

保护全部未提交改动；不要 reset、clean、stash、commit、push、切换分支或创建 PR。

必须完成：

1. 修复 HTTP 协议错误的状态映射
   - `HttpRequestMethodNotSupportedException` 返回 405 Problem Details，稳定 code 为
     `METHOD_NOT_ALLOWED`，保留正确的 `Allow` 响应头；
   - `HttpMediaTypeNotSupportedException` 返回 415 Problem Details，稳定 code 为
     `UNSUPPORTED_MEDIA_TYPE`；
   - 同时处理 406 `HttpMediaTypeNotAcceptableException`，避免它继续落入通用 500；
   - 所有响应保持 `application/problem+json`，包含 status、detail、instance、code、
     requestId、traceId，不泄露异常类名或内部消息；
   - 通用未知异常仍只负责真正的 500，不得用“全部改成 400”掩盖问题；
   - 增加真实 MockMvc 契约测试，至少覆盖 405、Allow、415、406；保留现有 500 脱敏测试。

2. 统一 UTC 存储和 API 解释
   - 选择并落实一套可说明的 UTC 方案，使干净 Compose 环境的 MySQL session、
     JDBC 连接和三个 Java 进程对 `DATETIME(6)` 的解释一致；
   - 建议将 `.env.example` 的 `TZ` 改为 `UTC`、`MYSQL_TIME_ZONE` 改为 `+00:00`，
     Compose 默认值和所有 JDBC `serverTimezone` 同步为 UTC；若采用等价方案，必须用测试证明；
   - 检查 `OrderTimeoutJob` 的 `LocalDateTime.now()`，确保统一后不会与数据库时间比较产生偏移；
   - 不删除、重建或修改用户当前 Docker volume。文档必须说明：旧的 `+08:00` 数据卷中的无时区
     `DATETIME` 历史值不会被配置变更自动换算，开发者应备份后自行选择一次性转换或重建本地数据；
   - 增加可重复的验证，证明干净环境中 MySQL `NOW()` 与 `UTC_TIMESTAMP()` 基本一致，并证明一个
     确定 UTC 时间经过 Mapper/DTO 后仍输出相同的 `Z` 时刻，时间筛选也使用相同语义；
   - 同步更新 `docs/api/api-contract.md`、`docs/runbooks/container-environment.md` 和验证记录。

3. 消除 TypeScript BIGINT URL 参数精度风险
   - Java Controller 可继续使用 `Long` 做绑定和范围校验，但运行时 OpenAPI 中所有 BIGINT
     path/query 参数必须声明为正十进制 `string`，不能是 `integer/int64`；
   - 至少覆盖 public/admin 的 `productId` path 参数，以及 admin Order/User 列表的 `userId`
     query 参数；
   - `orderId` path 参数补齐 1～64 位 `[A-Za-z0-9_-]` 契约和服务端校验；
   - 重新生成 runtime OpenAPI 和三组 TypeScript client，验证相关方法签名为 `string`/`string?`，
     不允许手工编辑生成文件；
   - 更新 OpenAPI 基线前先确认 diff 只包含本收口任务预期的状态响应、ID schema 和相关文档变化；
   - 为 OpenAPI schema 或生成输出增加自动检查，防止以后回退成 TypeScript `number`。

验收命令：

- 使用 Java 21、Docker socket 和仓库 Maven Wrapper 执行 `./mvnw -B clean verify`；
- `python -m unittest script.tests.test_check_openapi_compatibility`；
- 从运行中的 portal/admin jar 重新生成 OpenAPI 与 TypeScript client；
- `python script/check_openapi_compatibility.py --baseline docs/api/openapi-baseline --current target/openapi`；
- 用真实 HTTP 请求证明错误方法是 405 且有 Allow、错误媒体类型是 415、不可接受响应类型是 406，
  三者均为 Problem Details；
- 在隔离的 Compose project/volume 中验证 UTC，不得改写用户现有数据卷；
- `docker compose --env-file .env.example config --quiet`；
- `git diff --check`。

不得 skip tests、不得静态手写 OpenAPI、不得手改 TypeScript 生成物、不得通过删除兼容基线来制造通过。
结束时按 MASTER_PLAN.md 的交接模板报告实际编辑文件、测试数量、OpenAPI diff、生成客户端签名、
UTC 验证证据、命令结果和遗留风险。
```

### W3 最终验收：通过

TASK-04 与 TASK-04-RECONCILE-01 已于 2026-07-28 通过独立验收，W3 关闭：

- Java 21 与仓库 Maven Wrapper 完成 9 模块 `clean verify`；
- 56 tests、0 failures、0 errors、0 skipped，包含真实 MySQL Testcontainers；
- 405、415、406 由真实打包 Portal Jar 验证为 `application/problem+json`，405 保留 `Allow: POST`；
- public/user/admin OpenAPI 均从运行中 Jar 抓取，与基线逐字节一致；
- 6 个兼容门禁测试通过，三组 TypeScript client 可重复生成；
- `productId`、`userId`、`orderId` 的生成客户端 URL 参数均为 `string`；
- 隔离 Compose project 验证 MySQL global/session 时区均为 `+00:00`，`NOW()` 与
  `UTC_TIMESTAMP()` 相差 0 秒；临时容器和数据卷已清理，未操作用户现有卷；
- Compose 配置与 `git diff --check` 通过。

已有 `+08:00` 数据卷中的历史 `DATETIME` 不会被 UTC 配置自动换算；这是本机旧数据迁移事项，
不是 TASK-04 代码缺口。按运行手册先备份，再由数据所有者选择转换或重建可丢弃的开发数据。

## 7. 当前已发放波次：W4

W4 只包含 TASK-05。TASK-05 通过前不得开始 TASK-06、TASK-12 或 TASK-15。

### TASK-05 新对话提示

```text
你是 HotShop 的 TASK-05 实现工程师。W0～W3 已通过验收，本波次只有你修改工作区。

开始前完整阅读：
1. CONTEXT.md
2. docs/roadmap/MASTER_PLAN.md
3. docs/roadmap/TASK_CATALOG.md 的 TASK-05
4. docs/roadmap/PARALLEL_EXECUTION.md
5. docs/api/api-contract.md
6. docs/architecture/database-schema.md
7. docs/runbooks/container-environment.md
8. 当前 AuthController、AdminAuthController、SecurityConfig、JwtFilter、JwtTokenUtil、
   TokenBlacklistService、UserDetailsServiceImpl 与 refresh_token/audit_log 结构

只执行 TASK-05：分域鉴权与令牌轮换。不要开始统一审计查询、秒杀、可靠消息、前端或 Agent 业务工具。
保护全部未提交改动；不要 reset、clean、stash、commit、push、切换分支或创建 PR。

一、身份与调用边界

- 建立四个不可混用的调用身份：
  1. User Access：浏览器/用户调用 `/api/v1/**`；
  2. Administrator Access：管理员调用 `/admin/api/v1/**`；
  3. Agent Delegation：未来 Agent 后台代表 User 调用 `/agent/api/v1/**`；
  4. Service Identity：保留给未来真正的内部进程调用，不等同于 User、Administrator 或 Agent。
- 当前 `task` 进程在单体仓库内直接调用 Java service，不要为它伪造 `/internal/**` HTTP 接口；
  若未来出现内部 HTTP，必须使用独立 Service Identity/audience，禁止复用用户 JWT。
- 本任务为未来 Python Agent 实现一个最小、真实的 token-exchange 边界：
  Agent Service 使用自己的非对称 client assertion 证明 Service Identity，并同时提交一个有效的
  User Access Token；服务端验证两者后才签发 Agent Delegation。浏览器只有 User Token 时不能直接
  签发或伪造 Agent Delegation。
- client assertion 至少验证独立 issuer/audience、`typ`、`sub`、`iat`、短 `exp`、`jti`、签名、
  固定 Agent service client ID，并用 Redis 对 `jti` 做一次性防重放；TTL 不得超过 assertion 有效期。
- exchange 接口接受的 scope 必须与服务端 allowlist 取交集；请求包含未知或高风险 scope 时整体拒绝，
  不能静默授予，也不能接受 Administrator Access Token 作为 subject token。
- `/agent/api/v1/**` 本任务只建立鉴权边界和令牌验证能力，不创建虚假的 Agent 业务接口。
- Agent Delegation 必须包含被委托 User、Agent client/authorized party、允许的 scopes 和短过期时间；
  不得携带 Administrator 角色，不得刷新，不得调用 `/api/v1/**` 或 `/admin/api/v1/**`。
- 预定义最小 Agent scope allowlist，例如只读商品和本人订单/预约能力；库存改价、用户管理、
  管理员写操作、审计查询、密钥/权限管理等高风险后台能力永远不进入 Agent scope。
- 更新 CONTEXT.md，固定 Access Token、Refresh Session/Token Family、Agent Delegation、
  Service Identity、actor、delegated actor、scope 的唯一术语。

二、Access JWT

- 删除共享硬编码 HMAC secret，改用固定算法白名单的非对称签名，推荐 RS256；
- User、Administrator、Agent Delegation 使用独立 key pair 与独立 issuer/audience；
  至少做到密码学或验证配置上的严格隔离，不能依靠 role 字符串区分；
- Agent Service client assertion 另用独立 key pair；Java token-exchange 边界只配置其公钥，
  未来 Python Agent 才持有该 Service Identity 私钥；
- 私钥不得提交到 Git、写入镜像、OpenAPI、日志或 `.env.example`；运行时只给需要签名的进程私钥，
  验证进程只拿对应公钥；
- 提供可重复的本地密钥生成入口和忽略目录。脚本应优先使用 Docker 中的工具，不能要求开发者
  手工复制粘贴 PEM；不得覆盖已有密钥，除非调用者显式确认；
- JWT 至少验证 `alg`、`kid`、签名、`iss`、`aud`、`typ`、`sub`、`iat`、`nbf`、`exp`、`jti`；
  `sub` 使用稳定 User ID 字符串，不再用 username；
- Access Token 短时有效：User/Administrator 建议 10～15 分钟，Agent Delegation 不超过 5 分钟；
  允许的 clock skew 必须小且有文档；
- 支持 `kid` 和验证公钥集合，为零停机换钥留出路径，并写清“先发布验证公钥、再切签名 key、
  等旧 Access TTL、最后移除旧公钥”的顺序；
- Principal 从已验证声明构造，不能每个请求都查询数据库；说明禁用用户/角色变更最多受 Access TTL
  影响，以及何时需要额外撤销；
- 拒绝 `none`、错误算法、未知 `kid`、跨 issuer/audience、过期/尚未生效令牌和算法混淆攻击。

三、Refresh Token 与 Cookie

- Refresh Token 改为至少 256-bit CSPRNG 生成的 opaque 随机值，不再是 JWT；
- 数据库只保存 SHA-256 等不可逆 hash，绝不保存或记录明文 Refresh Token；
- User 与 Administrator 使用不同 cookie 名、不同 path、不同 issuer/audience 和独立 token family；
- Cookie 必须 HttpOnly、SameSite=Strict、host-only；生产配置必须 Secure，本机 HTTP 可通过显式
  local 配置关闭 Secure，不能把不安全默认带入生产；
- Access Token 只在响应 body 返回并由未来前端保存在内存，不放 localStorage；
- 登录/刷新响应增加 `Cache-Control: no-store` 和 `Pragma: no-cache`；
- Cookie 型 refresh/logout 接口必须有可实际接入前端的 CSRF 防护，例如双提交 CSRF cookie +
  `X-CSRF-Token`，并用常量时间比较；不能因为全局 Bearer API 无状态就完全关闭 Cookie 端点防护；
- refresh 不得要求一个仍有效的 Access Token。Access 已过期时，合法 Refresh Cookie 仍必须能轮换；
- 每次 refresh 在一个数据库事务中锁定当前记录、将其标记 ROTATED、插入唯一 successor，
  返回新的 Refresh Cookie；successor 的 `parent_token_id` 保持数据库唯一；
- 同一旧 token 再次使用视为 reuse：将 token 标记 REUSED，撤销整个 family 的全部活动 token，
  清除 cookie，并在同一数据库事务中追加一条脱敏安全审计事件；
- 本任务只写最小的 refresh-reuse/登录安全事件，不实现 TASK-06 的审计查询 API 或完整审计框架；
- logout 撤销对应 family 并清除 Refresh/CSRF cookie。说明当前 Access Token 是等待短 TTL 到期，
  还是使用 `jti` denylist 立即撤销；若使用 Redis denylist，key 只能包含 jti/hash，不能含原 token；
- 严格处理并发 refresh：数据库中最多一个 successor。若并发 loser 被判定为 reuse 并撤销 family，
  必须在契约与测试中明确；不得静默产生两条有效链；
- 已验收的 V1_0/V1_1 迁移视为不可变。任何 AUTO_INCREMENT、CSRF hash、会话元数据或索引变化
  使用新的 Flyway 迁移；继续保持 0 个 FOREIGN KEY。

四、授权与资源归属

- Portal 只接受 User audience；Admin 只接受 Administrator audience；Agent 边界只接受
  Agent Delegation audience。跨边界令牌应在认证阶段返回 401，而不是先信任后用 role 猜测；
- User 只能查询/创建属于自己的 Order，不接受客户端传入 userId 覆盖 Principal；
- Administrator 权限显式映射到管理方法；普通 User 不能依靠伪造 role/scope 调用；
- Agent 权限必须同时校验令牌类型、audience、authorized party、delegated User 和 scope，
  不得把 `ROLE_ADMIN` 或通配权限映射给 Agent；
- 移除 Portal `UserController` 对 Administrator Token 的兼容入口，避免跨域复用；
- 安全错误继续使用 TASK-04 Problem Details，不泄露“用户名是否存在”、密钥、token、cookie、
  hash、SQL 或内部异常。

五、分层限流

- 对 User 登录、Administrator 登录、refresh、Agent delegation/敏感认证入口建立不同限流策略；
- 登录至少组合可信客户端 IP 与 username hash；不要只按 username 锁号，也不要无条件信任
  `X-Forwarded-For`，只有显式配置可信代理时才读取转发头；
- 密码校验前先执行粗粒度 IP 限流，失败后再记录细粒度失败桶，避免 BCrypt 被用作 CPU DoS；
- 使用 Redis 原子 Lua 或等价原子实现，不允许 `GET` 后 `INCR` 的竞态窗口；
- key 中的 username/IP 等敏感值使用哈希或最小化表示，设置 TTL 和命名空间；
- 明确 Redis 不可用时登录/refresh 的 fail-open/fail-closed 策略。认证写入口建议返回脱敏 503，
  不能静默失去限流；429 必须含 `Retry-After`；
- 不要把 Redis 当作 Refresh Session 真相来源，数据库仍是 rotation/reuse 的事实来源。

六、测试与文档

- 使用真实 Spring Security filter chain；不能只用 `.with(user(...))` 冒充 JWT 验证；
- 使用 Testcontainers MySQL + Redis 和测试时生成的临时非对称 key pair，不依赖仓库私钥；
- 至少覆盖：
  - User/Administrator 正常登录与正确 audience；
  - User Token 调 Admin、Admin Token 调 Portal、Admin Token 冒充 Agent 均被拒绝；
  - token exchange 缺少/伪造 Service Identity、重放 client assertion、使用 Admin subject token、
    请求未知或高风险 scope 时被拒绝；
  - Agent Delegation 缺少 authorized party、越权 scope、管理员 scope 或跨边界时被拒绝；
  - 错误算法、未知 kid、错误 issuer/audience、过期、nbf、签名篡改；
  - Refresh 明文未入库、cookie/CSRF/no-store 属性正确；
  - Access 过期后仍可合法 refresh；
  - rotation 后旧 token reuse 撤销 family 并生成脱敏审计事件；
  - 两个线程并发 refresh，数据库最多一个 successor，结果与文档语义一致；
  - logout、已撤销/已过期 refresh、重复 logout；
  - 用户无法读取他人 Order；
  - 分层限流、Retry-After、Redis 故障策略；
- 增加敏感值扫描测试，至少证明日志、Problem Details、数据库审计摘要和 OpenAPI 不含私钥、
  Refresh Token、Cookie、密码或原始 Authorization；
- 更新 API 契约、OpenAPI 基线、TypeScript client 生成结果边界、数据库文档、密钥轮换与认证故障
  runbook、真实验证记录；
- 不要手改生成的 OpenAPI/TypeScript 文件，不要为尚未实现的 Agent 工具制造假成功接口。

验收命令至少包括：

- 使用 Java 21、Docker socket 和仓库 Maven Wrapper 执行 `./mvnw -B clean verify`；
- 所有 Testcontainers 身份测试实际执行且 0 skipped；
- 空库 Flyway migrate/validate、重复 migrate、0 FOREIGN KEY；
- 真实 HTTP/Cookie 流程演示：login → access → refresh rotation → 旧 refresh reuse →
  family revoked → 重新登录 → logout；
- 真实验证跨 audience、错误算法、过期与 Agent/Admin 隔离；
- 真实 Redis 限流与故障策略验证；
- 从运行中 portal/admin Jar 重新生成 public/user/admin OpenAPI 和 TypeScript client；
- 运行 OpenAPI 兼容门禁；
- `docker compose --env-file .env.example config --quiet`；
- `git diff --check`。

无法执行的验证必须保留原始错误，不得 skip tests、关闭安全校验、使用静态手写契约或内存假仓储
制造绿色结果。结束时按 MASTER_PLAN.md 的交接模板报告实际编辑文件、迁移版本、认证/授权矩阵、
JWT claims 与 key 边界、Cookie/CSRF 属性、refresh 状态机、限流策略、测试数量、命令结果和遗留风险。
```
