# TASK-05 真实验证记录

验证日期：2026-07-28。工作区：Windows + Docker Desktop 29.6.2；构建与测试 JVM：
`eclipse-temurin:21-jdk`（Java 21.0.11）；构建入口：仓库 Maven Wrapper。

## 1. 全量构建与容器测试

执行：

```powershell
docker run --rm `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  -v /var/run/docker.sock:/var/run/docker.sock `
  --mount "type=bind,source=$((Get-Location).Path),target=/workspace" `
  --mount "type=volume,source=hotshop-task05-m2,target=/root/.m2" `
  --workdir /workspace eclipse-temurin:21-jdk `
  sh -lc './mvnw -B clean verify'
```

TASK-05-RECONCILE-01 最终态复跑结果：`BUILD SUCCESS`，9 个 reactor 模块均成功，总耗时 03:15；
14 个测试套件共 96 tests，
0 failures、0 errors、0 skipped。身份测试共 15 个，0 skipped：

- Portal 11：RSA/issuer/audience/cookie/no-store、跨域、本人 Order、过期 Access 下 refresh、
  rotation/reuse/audit、并发 refresh、logout、token exchange、Agent claims、错误算法/kid/签名/
  issuer/audience/时间、分层限流、敏感值扫描、Redis fail-closed。
- Admin 4：独立 audience/cookie/permissions、refresh/logout、双向跨域、Admin 失败限流。
- Client address resolver 25：默认关闭、非可信 immediate peer、append 防伪、多层可信链、hostname/
  空元素/非法 IP/长度与 hop 限制、多行 header、IPv4/IPv6 规范化、非法配置 fail-closed、稳定 Redis
  key，以及 User/Admin login/failure/refresh 与 Agent exchange 共用同一解析器。参数化边界覆盖
  `192.0.2.1::`、错误位置的嵌入式 IPv4、重复 `::`、超范围分组，以及合法压缩/非压缩 IPv6。

独立 JShell 边界探针对 `192.0.2.1::`、`1:2:3:4:5:6:192.0.2.1::`、
`::ffff:192.0.2.1:5`、`1::2::3` 和 `2001:db8:::1` 逐项调用生产
`ClientAddressResolver.normalizeLiteral`，结果均为 `null`：

```text
unexpected_invalid_acceptances=0
```

数据库套件 21 tests，0 skipped。空库实际迁移 3 个版本至 V1.2；重复 migrate 显示无新增迁移；
validate 成功；legacy baseline 0 路径成功应用 V1.0/V1.1/V1.2；约束测试确认
`information_schema.referential_constraints` 为 0。

Testcontainers 在测试 JVM 退出并停止 Redis 后，Lettuce 后台线程会记录一次 reconnect warning；
这是容器 teardown 时的连接关闭，不是测试跳过或认证 fail-open。构建还记录 Mockito 动态 agent 的
未来 JDK 兼容 warning；Java 21 当前测试全部成功。

## 2. 独立网络真实 HTTP/Cookie 流程

先由 `.\script\generate-auth-keys.ps1` 生成被忽略的本地 key set，再执行：

```powershell
.\script\verify-task05-http.ps1
```

脚本用随机名字创建隔离 Docker network、MySQL 8.0.46、Redis 8.8.1、Flyway 11.20.3，以及从当前
构建产物启动的 portal/admin Java 21 Jar；只把每个进程所需的密钥文件单独只读挂载。验证结束精确
删除该次随机命名容器和 network，不操作已有 Compose volume。

实际结果：

```text
register User -> HTTP 201
register Administrator seed -> HTTP 201
User login -> HTTP 200
User Access -> Portal -> HTTP 200
refresh rotation -> HTTP 200
old Refresh reuse -> HTTP 401
successor after family revocation -> HTTP 401
User re-login -> HTTP 200
Administrator login -> HTTP 200
User Access -> Admin boundary -> HTTP 401
Administrator Access -> Portal boundary -> HTTP 401
Administrator Access -> Agent boundary -> HTTP 401
wrong JWT algorithm -> HTTP 401
expired User Access -> HTTP 401
User login before logout -> HTTP 200
User logout -> HTTP 200
denylisted Access after logout -> HTTP 401
TASK-05 isolated HTTP/Cookie verification passed
```

网络验证将 User Access TTL 显式设为允许的最小值 60 秒、clock skew 设为 0，并真实等待 61 秒，
没有通过伪造时钟制造过期成功。响应脚本从不打印 Access、Refresh、CSRF、密码或 Authorization。

## 3. OpenAPI、客户端和静态门禁

从 `clean verify` 生成的运行中 portal/admin Jar 抓取并规范化 public/user/admin 文档：

```powershell
.\script\update-openapi-baseline.ps1 -UseExistingPackages
.\script\generate-api-client.ps1 -UseExistingPackages
python .\script\check_openapi_compatibility.py
docker compose --env-file .env.example config --quiet
git diff --check
```

结果：

- 三份运行时 OpenAPI 与基线生成成功；没有手工编辑 JSON。
- OpenAPI Generator 7.14.0 重新生成 public/user/admin TypeScript client 到 `target/`，构建成功。
- 兼容门禁：`OpenAPI compatibility check passed for public, user, and admin contracts.`
- Compose 配置：退出码 0；Git whitespace 检查：退出码 0。
- Generator 仍输出既有 OpenAPI 3.1 beta 与相对 server localhost fallback warning；调用方必须继续
  显式设置 `Configuration.basePath`，生成产物没有把 fallback 当生产地址。

## 4. 敏感值证据

`IdentitySecurityTest.sensitiveValuesAreAbsentFromLogsProblemsAuditAndOpenApi` 对运行时日志 appender、
Problem Details、数据库安全审计摘要和运行时 OpenAPI 使用标记值扫描。登录测试同时直接查询
`refresh_token`，证明明文 Refresh/CSRF 不在库中；reuse 审计摘要不含旧 token、successor 或 CSRF。
仓库源文件扫描没有 HMAC secret/HS256 签发配置；`.local/keys/` 被 `.gitignore` 和 `.dockerignore`
排除，Compose 不把 Agent Service 私钥挂载给 Java 进程。
