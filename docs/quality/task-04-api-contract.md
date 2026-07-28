# TASK-04 API 契约与错误规范验证记录

> 执行日期：2026-07-27。所有 Java 命令使用 `eclipse-temurin:21-jdk` 容器和仓库 Maven Wrapper；
> 没有 `skipTests`、静态手写 OpenAPI、commit、push、分支或 PR 操作。

## 已验证设计

- 正式路径只使用 `/api/v1` 与 `/admin/api/v1`；`/agent/api/v1` 只保留分组边界。
- Controller 的 HTTP 输入/输出只包含 DTO，生成 schema 不含 `PageInfo`、持久化实体或密码。
- public、user、admin JSON 均从运行中的 Spring Boot jar 的 `/v3/api-docs/{group}` 抓取。
- 金额和 JSON BIGINT ID 生成 TypeScript `string`；必填响应字段生成非可选属性。
- Order keyset Mapper 在相同时间和并发插入下保持稳定。
- Spring Security/MockMvc 覆盖匿名成功、User 成功、Admin 成功、401、403、400、404、409、429、500
  和旧路径 404。
- 兼容脚本的四个变异自测分别证明删除 path、删除 field、改变 type、收紧 required 会失败。

## 已执行命令与结果

### 定向契约测试和打包

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task04-m2,target=/root/.m2" `
  --workdir /workspace `
  eclipse-temurin:21-jdk `
  ./mvnw -B -pl portal,admin -am package
```

结果：`BUILD SUCCESS`。本次命令实际执行 common 5、domain 6、portal 11、admin 5，共 27 tests，
0 failures、0 errors、0 skipped。

### 运行时 OpenAPI 与 TypeScript

```powershell
.\script\update-openapi-baseline.ps1 -UseExistingPackages
.\script\generate-api-client.ps1 -UseExistingPackages
```

结果：public、user、admin 三份运行时 JSON 生成成功；OpenAPI Generator 7.14.0 生成三组
`typescript-fetch` client，`BUILD SUCCESS`。生成器如实输出 “OpenAPI 3.1 support is still in beta”
警告，不影响退出码。运行时 JSON 的 `servers` 均为稳定同源地址 `/`；生成文件边界为
`target/openapi` 与 `target/generated-sources/typescript`，不得手工修改或提交。连续两次运行时抓取经
确定性 JSON 规范化后与基线逐字节一致；public、user、admin 的 SHA-256 分别为
`56E39B76...05DC182`、`52CFDB9C...24702859`、`4AC6A6DA...B2CE9B57`。
生成器还会对合法同源相对 server `/` 报 localhost fallback 警告；客户端接入方必须按环境设置
`Configuration.basePath`，不得采用 fallback 作为生产地址。

### 兼容门禁与变异自测

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -m unittest script.tests.test_check_openapi_compatibility
python script/check_openapi_compatibility.py `
  --baseline docs/api/openapi-baseline `
  --current target/openapi
```

结果：4 tests passed；当前 public、user、admin 相对生成基线兼容，退出码 0。

## 最终验收

```powershell
docker run --name hotshop-task04-clean-verify `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task04-m2,target=/root/.m2" `
  --mount "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock" `
  --workdir /workspace `
  eclipse-temurin:21-jdk `
  ./mvnw -B clean verify
```

结果：`BUILD SUCCESS`，9 个 reactor 模块全部成功，总耗时 2:04。实际执行 common 5、domain 6、
database 20、portal 11、admin 5、task 3，共 50 tests；0 failures、0 errors、0 skipped。
database 测试使用 Docker socket 启动真实 MySQL 8.0.46 Testcontainers。

首次定向 database 验证没有设置 Docker Desktop 主机覆盖时，保留的原始环境错误为
`IllegalStateException: Could not connect to Ryuk at 172.17.0.1:51721`。设置
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` 后，同一条未跳过测试的命令通过：
database 20/20。没有禁用 Ryuk 或测试。

```powershell
docker compose --env-file .env.example config --quiet
git diff --check
```

结果：两个命令退出码均为 0。`git diff --check` 仅输出工作区现有 CRLF 将来会规范为 LF 的
提示，没有 whitespace error。

## TASK-04-RECONCILE-01 收口验证（2026-07-28）

本次仅收口 HTTP 协议错误、UTC 解释与 BIGINT URL 参数三项契约问题，没有开始 TASK-05，也没有
改变鉴权、交易、消息或分页架构。

### HTTP 协议错误

- `HttpRequestMethodNotSupportedException` 映射为 405 / `METHOD_NOT_ALLOWED`，并保留 Spring
  计算出的 `Allow` 响应头。
- `HttpMediaTypeNotSupportedException` 映射为 415 / `UNSUPPORTED_MEDIA_TYPE`。
- `HttpMediaTypeNotAcceptableException` 映射为 406 / `NOT_ACCEPTABLE`。
- 三类错误均为 `application/problem+json`，包含 `status`、`detail`、`instance`、`code`、
  `requestId` 与 `traceId`；通用未知异常仍只处理真正的 500。
- Portal MockMvc 新增 405（含 `Allow`）、415、406 三项真实 Spring MVC/Security 契约测试；
  原有 500 脱敏测试继续通过。

使用真实 portal jar 和隔离的 Compose project/volume 发起 HTTP 请求，结果为：

```text
HTTP verification passed: project=hotshop-task04-http-d77c7c2989
405=METHOD_NOT_ALLOWED Allow=POST
415=UNSUPPORTED_MEDIA_TYPE
406=NOT_ACCEPTABLE contentType=application/problem+json
```

验证结束后只删除了该随机 project 的容器与 volume，没有操作用户现有 HotShop volume。

### UTC 一致性

- `.env.example`、Compose 默认值、MySQL `--default-time-zone`、全部 JDBC `serverTimezone`、
  Hikari `connectionTimeZone`/`forceConnectionTimeZoneToSession` 和三个 JVM 的 `TZ` 已统一为
  UTC。
- `OrderTimeoutJob` 使用注入的 UTC `Clock` 计算无时区 `DATETIME(6)` 阈值，测试用固定时钟证明
  传给 Mapper/Service 的时间没有本地时区偏移。
- MySQL Testcontainers 测试证明确定的 UTC `LocalDateTime` 经 Mapper 时间筛选和
  `ApiDtoMapper` 后仍输出相同的 `Z` 时刻。

隔离 Compose UTC 验证结果：

```text
UTC verification passed: project=hotshop-task04-utc-cf068ab3f3
global=+00:00 session=+00:00 deltaSeconds=0
```

该验证使用随机 project 和独立 volume，并在结束时删除它们。已有 `+08:00` 数据卷中的
`DATETIME` 历史值不会被配置变化自动换算；必须先备份，再由开发者选择一次性转换，或仅对可丢弃
的纯开发数据手动重建数据卷。本任务没有修改或重建用户现有数据卷。

### OpenAPI 与 TypeScript BIGINT

运行时 OpenAPI 将 path/query 中的 `productId`、`userId` 声明为 1～19 位正十进制
`string`；Java Controller 仍使用 `Long` 做绑定和范围校验。`orderId` path 参数声明并校验
1～64 位 `[A-Za-z0-9_-]`。

更新基线前的结构化 diff 只包含本收口范围：

- public：新增 12 个协议错误响应、3 个 Problem 响应组件、1 个 ID 参数 schema 变化；
- user：新增 15 个协议错误响应、3 个 Problem 响应组件，无 ID 参数变化；
- admin：新增 30 个协议错误响应、3 个 Problem 响应组件、6 个 ID 参数 schema 变化；
- 未发现路径、成功响应内容或其他 schema 的非预期变化。

最终从真实 portal/admin jar 再次生成 OpenAPI 和三组 `typescript-fetch` client。当前 JSON 与
基线逐字节一致，SHA-256 为：

```text
public 53500BABAE765E426E7AF904279AC35349A8B8CA2270346E771699D6CDEF8A98
user   06D493589060F1CDD814E4FA65F45801D6E9B852C2921D3556F4D1E714ACFC99
admin  FA390FE0C90B22C4DF174D62CE9EEB942F073AE514E7D95F80E0D99A481B69AC
```

生成客户端签名为 `productId: string`、`orderId: string`、`userId?: string`。兼容脚本增加
HotShop URL ID 不变量检查，能阻止它们回退为 `integer/int64` 或无约束 `orderId`；生成目录仍为
`target/generated-sources/typescript`，没有手工编辑生成物。

### 收口命令结果

```powershell
docker run --name hotshop-task04-reconcile-final-verify `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task04-m2,target=/root/.m2" `
  --mount "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock" `
  --workdir /workspace `
  eclipse-temurin:21-jdk `
  ./mvnw -B clean verify
```

结果：`BUILD SUCCESS`，9 个 reactor 模块全部成功，总耗时 2:10。实际执行 common 5、domain 6、
database 21、portal 14、admin 6、task 4，共 56 tests；0 failures、0 errors、0 skipped。
database 测试通过 Docker socket 使用真实 MySQL Testcontainers。

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -m unittest script.tests.test_check_openapi_compatibility
python script/check_openapi_compatibility.py `
  --baseline docs/api/openapi-baseline `
  --current target/openapi
.\script\generate-api-client.ps1 -UseExistingPackages
.\script\verify-compose-utc.ps1
docker compose --env-file .env.example config --quiet
git diff --check
```

结果：Python 6 tests passed；兼容检查通过；运行时 OpenAPI 与三组客户端生成成功；隔离 UTC 检查
通过；Compose 配置检查退出码 0；`git diff --check` 无 whitespace error，仅报告工作区原有文件
未来 CRLF 到 LF 的提示。

调试期间保留的原始失败为：UTC 脚本首次执行因 PowerShell 向 MySQL CLI 传递 SQL 的引号被吞掉而
收到 MySQL 1064，改为标准输入传 SQL 后通过；406 MockMvc 测试首次因未设置商品 fixture 而先返回
404，补齐成功查询 fixture 后由内容协商层稳定返回 406。两项均未通过跳过测试或放宽断言规避。
