# HotShop TASK-00 验证基线

> 执行日期：2026-07-26；工作目录：仓库根目录。结果只代表本次实际执行，不把未运行、被跳过或
> 依赖解析失败的测试写成通过。
>
> 第 1～5 节保留 TASK-00 当时的历史证据；当前 TASK-09-RECONCILE-01 的可复现结果见第 6 节。

## 1. 快照与环境

TASK-00 开始时已有以下用户修改，均被保留：

```text
 M Dockerfile
 M docker-compose.yml
?? .dockerignore
?? docker/rabbitmq/
?? docs/
```

工具探测结果：

| 工具 | 实际结果 |
| --- | --- |
| Docker Client/Server | 29.6.2 / 29.6.2 |
| Docker Compose | v5.3.1 |
| Maven image | `maven:3.9.9-eclipse-temurin-17`，本机已有镜像 |
| Maven Wrapper | `mvnw`、`mvnw.cmd` 均不存在 |
| Host Java | `java -version` 无输出并以 Windows 进程码 `-1073740791` 退出 |
| Host Maven | `mvn.cmd` 可找到，但在上述 Java 状态下未得到可信版本/构建输出 |

Compose 快照中六个既有容器均为 `Exited (255)`；TASK-00 没有把该状态当作服务可用证据。

## 2. 实际执行记录

### 2.1 成功项

#### Compose 语法解析

命令：

```powershell
docker compose config --quiet
```

结果：退出码 0。仅证明当前 Compose 文件可解析，不证明镜像可构建或服务健康。

#### UTF-8 静态扫描

命令（等价摘要）：

```powershell
$strict = [Text.UTF8Encoding]::new($false, $true)
Get-ChildItem -Recurse -File |
  Where-Object { $_.FullName -notmatch '\\(\.git|target)\\' } |
  ForEach-Object { $null = $strict.GetString([IO.File]::ReadAllBytes($_.FullName)) }
```

结果：受检的 Java、XML、YAML、Markdown、SQL、脚本与忽略文件均可严格按 UTF-8 解码；
未发现 UTF-8 BOM 或常见 mojibake 片段。第一次直接使用 Windows PowerShell 默认
`Get-Content` 读取路线文档时出现显示乱码，显式使用 UTF-8 后内容正常。

### 2.2 失败项

#### Host Java

命令：

```powershell
java -version
```

结果：无版本输出，进程退出码 `-1073740791`。因此没有使用宿主机 Java 声称 Maven 测试通过。

#### 改动前全量 Maven 测试

为绕过宿主机 Java 异常，实际使用固定 Maven/JDK 17 容器：

```powershell
docker run --name hotshop-task00-baseline-test `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task00-m2,target=/root/.m2" `
  --workdir /workspace `
  maven:3.9.9-eclipse-temurin-17 mvn -B -ntp test
```

结果：失败，退出码 1，总耗时 4 分 03 秒。

- `hotShop`：SUCCESS；
- `common`：SUCCESS（无测试）；
- `infrastructure`：FAILURE；
- `domain`、`security`、`portal`、`admin`、`task`：SKIPPED；
- 原始失败摘要：从 Maven Central 解析
  `io.netty:netty-handler/netty-buffer/netty-transport:4.1.118.Final` 与
  `org.reactivestreams:reactive-streams:1.0.4` 时，远端终止 TLS handshake。

该次执行没有进入现有测试或 TASK-00 新增测试，不能视为测试失败或通过的功能证据。

## 3. TASK-00 特征测试

新增测试只锁定当前可稳定描述的行为，不修改生产代码：

| 测试 | 描述 |
| --- | --- |
| `OrderStatusCharacterizationTest` | 当前 PENDING 转移、终态与英文持久化枚举解析 |
| 已删除的旧 Rabbit 发布特征测试 | 曾记录不可靠直接发送和错误时间换算；TASK-09 已用可靠性测试替代 |
| 已删除的旧超时消费者特征测试 | 曾记录 Redis 锁和旧 ACK 行为；TASK-09 已用 Inbox/DLQ 测试替代 |

旧 Rabbit 发布特征测试曾对 2 分钟断言错误的 60,000ms，并在测试显示名中明确
标记 `Current known defect (TASK-09)`。这是特征化旧行为，不表示分钟换算正确；TASK-09 修复时
应先把该断言改成正确需求再修改生产实现。

定向命令最终执行结果：BUILD SUCCESS，总耗时 25.340 秒。

- `OrderStatusCharacterizationTest`：3 passed；
- 已删除的旧 Rabbit 发布特征测试：历史 2 passed；
- 已删除的旧超时消费者特征测试：历史 3 passed；
- 合计：8 tests，0 failures，0 errors，0 skipped。

成功执行使用了 `hotshop-task00-m2` 临时 Maven 缓存卷。Java/Maven 的 TLS 下载连续两次无法取得
`jackson-module-parameter-names:2.18.2` 和 `tomcat-embed-el:10.1.36` 后，使用同一 Maven
容器中的 `wget` 从 Maven Central 直接把这两个 artifact 的 POM/JAR 放入该临时卷，再以完全相同
的测试命令执行。此操作没有修改仓库依赖声明或生产代码；它是本次环境规避措施，不是通用构建修复。

### 3.1 未筛选的 common/domain/task 测试

命令：

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task00-m2,target=/root/.m2" `
  --workdir /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn -B -ntp -pl task -am test
```

结果：BUILD FAILURE，总耗时 52.563 秒。

- `common`：3 tests passed；
- `domain`：现有 4 个 `OrderServiceTest` 与新增 2 个 RabbitMQ 特征测试全部通过；
- `task`：新增 3 个消费者特征测试通过；
- `task` 当时的旧 Rabbit 联通测试（现已删除）：ERROR，
  `AmqpConnectException: java.net.ConnectException: Connection refused`，目标是
  `localhost:5672`；
- 总计实际进入 13 个测试：12 passed，1 error，0 skipped。

测试上下文当时还启动了现已删除的定时扫描器，它连接 `localhost:3306` 失败并在调度线程记录
`CannotCreateTransactionException`。这再次证明旧 `@SpringBootTest` 不是可独立执行的单元测试；
失败没有被隐藏或改成 skip。

## 4. 统一的本地验证命令

TASK-00 当时仓库没有 Maven Wrapper；以下命令只保留为历史记录。当前仓库已提供 Wrapper，
TASK-09 的 Java 21 验证命令见第 6 节。

### 4.1 首选：已安装可用 JDK 17 与 Maven 3.9.x

```powershell
mvn -B -ntp test
```

### 4.2 固定容器环境

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --workdir /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn -B -ntp test
```

### 4.3 只运行 TASK-00 特征测试

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --workdir /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn -B -ntp -pl common,domain,task -am `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dtest=OrderStatusCharacterizationTest,ReliableRabbitTopologyTest,OutboxPublisherTest" `
  test
```

Linux/macOS 使用相同 Docker 参数，将 PowerShell 续行符反引号改为反斜杠。首次执行需要从 Maven
仓库下载依赖；网络/TLS 失败必须保留为失败，不应通过 `-DskipTests` 伪造绿色结果。

首次定向执行时未给 `-D` 参数加引号，PowerShell 将参数传成
`.failIfNoSpecifiedTests=false`，Maven 以 `Unknown lifecycle phase` 在 0.53 秒内退出；测试没有
执行。上面的统一命令已经按实际重跑所需的形式修正。

## 5. 外部依赖验证边界

- 已删除的旧 Rabbit 联通测试曾加载本机 MySQL、Redis 和 RabbitMQ
  相关上下文，并向未声明的 `testQueue` 发送消息；它既不是隔离单测，也没有结果断言。
- TASK-00 当次没有成功启动 Compose 基础设施，因此当时没有 API、数据库集成、Redis 或 RabbitMQ 端到端
  通过证据。
- `docker compose config --quiet` 只属于静态配置验证。
- TASK-00 当时尚待全量重跑；后续任务已经完成测试分层与独立容器化验证。

## 6. TASK-09-RECONCILE-01 当前验证基线

执行日期：2026-08-01。Java 门禁在 Java 21 Maven 容器中从干净的 `target` 目录执行，命令为：

```powershell
./mvnw -B clean verify
```

结果：`BUILD SUCCESS`，9 个 Reactor 模块全部成功，总耗时 5 分 05 秒。Surefire 汇总为
181 tests、0 failures、0 errors、0 skipped；模块测试数量为 common 5、domain 7、database 29、
security 30、portal 40、admin 21、task 49。

正式可靠性套件 `ReliableMessagingContainerTest` 使用全新 MySQL 8.0.46 与
`rabbitmq:4.0.7-management-alpine` Testcontainers，18 tests 全部通过。它替代了旧的本机 Rabbit
联通测试和旧超时特征测试，并对 broker confirm/return、有限重试、FAILED 人工重放、租约接管与
fencing、TTL/DLX、崩溃重复投递、Inbox 幂等、毒消息 DLQ、库存回滚及秒杀隔离作业务断言。

同次门禁还实际通过 `docker compose config --quiet`、三套 OpenAPI 客户端漂移检查、Web 的格式、
lint、类型、14 个单元测试与生产构建，以及 Chromium 桌面/移动端 Playwright 6 tests。
