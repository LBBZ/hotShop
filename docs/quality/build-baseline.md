# HotShop TASK-01 构建基线

> 执行日期：2026-07-26；工作目录：仓库根目录。本文只记录实际执行结果；未启动外部依赖时失败的
> 集成测试不会被写成通过，也没有使用 `skipTests`、`maven.test.skip` 或测试条件禁用来制造绿色结果。

## 1. 选定版本与构建约束

| 项目 | 版本或策略 |
| --- | --- |
| Java | 21；编译使用 `--release 21`，Enforcer 接受范围 `[21,22)` |
| Spring Boot | 3.5.14，统一由 `spring-boot-starter-parent` 管理 |
| Spring Framework | 6.2.18，由 Spring Boot 3.5.14 BOM 管理 |
| MyBatis Spring Boot Starter | 3.0.5 |
| MyBatis / MyBatis-Spring | 3.5.19 / 3.0.5 |
| Maven | Wrapper 固定 3.9.16，Enforcer 接受范围 `[3.9.16,4.0.0)` |
| Maven Wrapper | 3.3.4 `bin` 分发；Wrapper JAR 与 Maven ZIP 都固定 SHA-256 |
| 单元测试 | Surefire 3.5.4，默认匹配 `**/*Test.java` |
| 集成测试 | Failsafe 3.5.4，仅在 `integration-tests` profile 中匹配 `**/*IT.java` |
| 编码 | 源码、资源、报告统一 UTF-8；`.gitattributes` 固定跨平台文本行尾 |

版本选择依据：

- Spring Boot 官方 Releases 在执行日将 3.5.14 列为 3.5.x 最新稳定补丁：
  <https://github.com/spring-projects/spring-boot/releases/tag/v3.5.14>。
- MyBatis Spring Boot 3.0.5 的官方发布说明明确把基线切换到 Spring Boot 3.5.x：
  <https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-3.0.5>。
- Apache Maven 官方下载页在执行日推荐 Maven 3.9.16：
  <https://maven.apache.org/download.cgi>。

根 POM 不再逐个固定 Spring Boot Starter、Spring Security、Spring AMQP、Spring Data 或测试组件的
Spring 版本，避免与 Boot BOM 产生双重版本来源。保留显式版本的第三方组件集中在父 POM 管理。

## 2. Wrapper 与依赖解析策略

Wrapper 使用官方 `bin` 类型，而不是 `only-script`。实际验证发现，`only-script` 在没有 `unzip` 的
精简 Linux 镜像中会自动改下 `.tar.gz`，但仍以 ZIP 的 SHA-256 校验，从而正确拒绝启动。`bin`
类型提交 63,093 字节的官方 Wrapper JAR，由 Java 下载并解压固定的 Maven ZIP，不要求镜像预装
`unzip`。

`.mvn/maven.config` 对所有 Wrapper 构建统一启用：

- 无传输进度噪声；
- 严格 checksum 校验；
- Maven Resolver HTTP 请求最多重试 5 次，初始间隔 2 秒。

父 POM 删除了阿里云和重复 Central 的 `pluginRepositories`，依赖与插件只使用 Maven 默认的规范
Central 地址。这样不会因两个仓库内容或同步时点不同而改变解析结果。

TASK-00 的 TLS 问题在本次通过以下证据得到明确规避：

1. 使用全新的 Docker volume `hotshop-task01-m2`，没有手工注入 POM/JAR；
2. 从规范 Maven Central 完成全量依赖解析和 8 模块 `clean verify`；
3. 复用该缓存并给容器设置 `--network none` 后，离线 `clean verify` 再次通过。

首次联网仍然依赖 Maven Central 可用性；重试耗尽、checksum 不匹配或 artifact 缺失都会让构建失败，
不会回退到未经声明的镜像或跳过测试。

## 3. 单元测试与集成测试分层

默认命令：

```powershell
.\mvnw.cmd -B clean verify
```

```bash
./mvnw -B clean verify
```

默认生命周期只由 Surefire 执行 `*Test` 单元测试。原
`task/src/test/java/com/real/task/test/RabbitMQConnectionTest.java` 已改名为
`RabbitMQConnectionIT.java`，只有显式启用下列 profile 时才由 Failsafe 执行：

```powershell
.\mvnw.cmd -B -pl task -am -Pintegration-tests verify
```

```bash
./mvnw -B -pl task -am -Pintegration-tests verify
```

该 profile 不跳过测试；缺少 MySQL、Redis 或 RabbitMQ 时应失败。当前测试仍使用应用默认的
`localhost:3306`、`localhost:6379`、`localhost:5672`。待后续测试基础设施任务提供可复现环境后，
应使用同一显式 profile 运行并记录通过证据。

## 4. 实际执行记录

### 4.1 Wrapper 生成

命令：

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task01-m2,target=/root/.m2" `
  --workdir /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn -B -ntp -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper `
  "-Dmaven=3.9.16" "-Dtype=bin" `
  "-DdistributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce"
```

结果：`BUILD SUCCESS`，总耗时 1.413 秒。随后为提交的 Wrapper JAR 增加
`wrapperSha256Sum=4e2fbf6554bc8a4702cdfdd3bef464f423393d784ddbb037216320ce55d5e4e1`。

### 4.2 Linux Wrapper 启动

命令：

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task01-m2,target=/root/.m2" `
  --workdir /workspace `
  eclipse-temurin:21-jdk ./mvnw --version
```

结果：退出码 0。实际输出 Maven 3.9.16、Java 21.0.11、Linux amd64、平台编码 UTF-8。基础镜像未预装
`unzip`，`bin` Wrapper 仍成功完成校验、下载和启动。

### 4.3 Linux 全量默认构建

首次使用新 Maven 缓存的命令：

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task01-m2,target=/root/.m2" `
  --workdir /workspace `
  eclipse-temurin:21-jdk ./mvnw -B clean verify
```

结果：`BUILD SUCCESS`；8 个 reactor 模块全部成功；总耗时 2 分 23 秒。实际执行 12 个单元测试：

- `common`：3 passed；
- `domain`：6 passed；
- `task`：3 passed；
- 合计：12 passed，0 failures，0 errors，0 skipped。

移除重复的 Spring Boot repackage 执行后，用相同命令复跑：`BUILD SUCCESS`，总耗时 35.488 秒；
模块与测试结果相同。

### 4.4 Windows Wrapper 与全量默认构建

TASK-00 已记录宿主机原 Oracle `java.exe` 异常。本次没有修改系统 Java，而是在系统临时目录解压
Temurin 21.0.11，并仅为当前命令设置 `JAVA_HOME`，随后执行：

```powershell
cmd /c mvnw.cmd --version
cmd /c mvnw.cmd -B clean verify
```

结果：

- `mvnw.cmd --version`：退出码 0；Maven 3.9.16、Java 21.0.11、Windows 11 amd64、平台编码 UTF-8；
- `mvnw.cmd -B clean verify`：`BUILD SUCCESS`；8 个模块全部成功；总耗时 2 分 16 秒；
- 12 passed，0 failures，0 errors，0 skipped。

### 4.5 断网离线复建

命令：

```powershell
docker run --rm --network none `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task01-m2,target=/root/.m2" `
  --workdir /workspace `
  eclipse-temurin:21-jdk ./mvnw -B -o clean verify
```

结果：`BUILD SUCCESS`；8 个模块全部成功；总耗时 35.662 秒；12 passed，0 failures，0 errors，
0 skipped。该结果只证明已解析并校验过的依赖缓存可离线复用，不宣称空缓存可以离线构建。

### 4.6 显式外部依赖集成测试

命令：

```powershell
docker run --rm `
  --mount "type=bind,source=$PWD,target=/workspace" `
  --mount "type=volume,source=hotshop-task01-m2,target=/root/.m2" `
  --workdir /workspace `
  eclipse-temurin:21-jdk `
  ./mvnw -B -pl task -am -Pintegration-tests verify
```

结果：`BUILD FAILURE`，总耗时 36.776 秒。

- profile 启用前的单元测试仍为 12 passed；
- Failsafe 实际执行 `RabbitMQConnectionIT`：1 test，0 failures，1 error，0 skipped；
- 原始失败为 `AmqpConnectException: java.net.ConnectException: Connection refused`，
  目标 `localhost:5672`；
- 应用定时任务还记录 `CannotCreateTransactionException`，MySQL 目标 `localhost:3306` 未启动；
- 失败最终由 `maven-failsafe-plugin:3.5.4:verify` 传播为 Maven 退出码 1。

这证明外部依赖测试已从默认单元测试中隔离，且显式运行时不会被静默跳过或伪装成绿色。

### 4.7 关键依赖解析检查

命令：

```bash
./mvnw -B -pl domain dependency:tree \
  -Dincludes=org.mybatis.spring.boot:mybatis-spring-boot-starter,org.mybatis:mybatis,org.mybatis:mybatis-spring,org.springframework.boot:spring-boot,org.springframework:spring-core
```

结果：`BUILD SUCCESS`。实际解析到：

- `mybatis-spring-boot-starter:3.0.5`；
- `mybatis:3.5.19`；
- `mybatis-spring:3.0.5`；
- `spring-boot:3.5.14`；
- `spring-core:6.2.18`。

第一次尝试在新增 `dependency:tree`/`help:evaluate` 插件尚未进入缓存时以 `-o` 运行，Maven 因离线
无法解析诊断插件而退出码 1；改为联网解析后上述命令成功。这不影响已经离线通过的默认
`clean verify`，但说明“离线可复建”的边界是构建生命周期所需 artifact，而不是任意未预热的诊断插件。

## 5. 已知风险

- 单元测试运行时 Mockito 记录动态加载 Byte Buddy agent 的未来兼容性警告；Java 21 当前测试通过，
  但未来 JDK 默认禁止动态 agent 后需要按 Mockito 官方建议显式配置 `-javaagent`。
- `RabbitMQConnectionIT` 仍是旧的联通测试，只验证发送调用未抛异常，没有验证消息最终被消费；
  TASK-01 只负责正确分层，没有改变其业务语义。后续消息测试任务应以 Testcontainers 或受控 Compose
  环境补充可断言的投递结果。
- 当前仓库没有为集成测试提供独立 Testcontainers 配置；因此本次只能如实记录外部依赖未启动时的
  失败，不能声称集成测试通过。
