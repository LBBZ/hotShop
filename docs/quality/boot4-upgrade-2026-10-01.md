# 2026-10-01 Spring Boot 4 升级记录

本轮承接 [2026-09-30 优化](modernization-2026-09-30.md)，在 `codex/modernize-hotshop` 分支继续开发。源码读写、依赖解析、构建与验证均在 Docker 中进行。复用 `hotshop-modernize-0930` 的数据、密钥与服务，不另建整套演示环境；原有项目与数据卷保留。

## 依赖与迁移范围

| 组件 | 升级前 | 本轮 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | 4.1.1 |
| 应用 JSON | Jackson 2 API | Jackson 3.1.7 |
| MyBatis Spring Boot Starter | 3.0.5 | 4.1.0 |
| PageHelper Starter | 2.1.1 | 4.1.1 |
| springdoc | 2.8.17 | 3.1.1 |
| JJWT | 0.11.5 | 0.13.0 |
| RabbitMQ Java Client | 5.33.1 | 5.36.0 |
| Testcontainers | 1.x | 2.0.5 |

Java 21、Maven Wrapper 3.9.16 和 Flyway 11.20.3 保留。Flyway 与现有一次性 migrator 对齐，没有新增自动迁移 starter，也没有新建数据库迁移。MyBatis **核心仍为 3.5.19**，本次升级的是 Spring 集成；PageHelper 核心为 6.1.1。显式统一 MyBatis autoconfigure 4.1.0，避免 PageHelper 传递依赖带回 4.0.x。

Boot BOM 管理 Spring Framework 7.0.9、Spring Security 7.1.1、Tomcat 11.0.24、Netty 4.2.17.Final、OpenTelemetry 1.62.0。JJWT 和 Swagger 内部仍需要 Jackson 2，统一保留 2.21.7；不能宣称依赖树彻底移除 Jackson 2。Jackson annotations 的官方包名仍为 `com.fasterxml.jackson.annotation`，版本仍采用 2.x 编号。

Rabbit 客户端保留独立覆盖：Boot 4.1.1 BOM 默认 5.30.0，早于官方 AMQP 输入解析修复。初轮构建核对发现这次隐式降级后，改为 5.36.0 并重新执行最终回归与构建。官方披露包括过大分配、递归结构、帧大小上限绕过及特定 URI 配置异常中的凭据泄露；不将“依赖受影响”等同于本项目已被利用。[5.36.0 发布说明](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.36.0)、[分配上限修复](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-68mj-5wr7-6fgg)、[递归修复](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-93j5-89vc-pph4)、[帧大小修复](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-jh4v-gfqj-7rhx)、[URI 修复](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-h6w7-qmcm-q6xr)。

## 保持的业务契约

- 使用 Boot 4 的 Web MVC、AspectJ、测试及 OpenTelemetry starter，更新模块化后的自动配置导入；显式引入 Web 参数校验。
- Jackson 3 使用不可变 mapper builder 和新的异常类型。严格金额字符串、长整数 ID、整数边界与 Problem 响应格式保持；解析异常继续进入 400、401、消息死信或 Outbox 无效载荷处理。
- JWT 保留 RS256、字符串 `aud`、三种身份域、签发与校验共用 Clock 及原钟差规则。新版 JJWT 的 audience 校验是包含关系，额外要求单一精确 audience，避免扩大原授权范围。
- Rabbit 使用 Jackson 3 converter；既有无 Java 类型头的消息及包含 JsonNode 的 Outbox JSON 保持兼容。Redis 继续使用字符串序列化，不迁移已有缓存格式。
- Portal/Admin 使用 Boot 4 的 datetime 配置路径，保留日期字符串。追踪 endpoint 改用 `management.opentelemetry.tracing.export.otlp.endpoint`。
- 新 starter 默认引入 OTLP 指标推送。本轮显式关闭 `management.otlp.metrics.export.enabled` 和 `management.logging.export.otlp.enabled`，保持 Prometheus 拉取指标、JSON stdout + Alloy 日志采集、OTLP 追踪。回归使用本地 HTTP 接收端真实接收 span，并同时检查 Prometheus counter 可抓取、没有 OTLP metrics/log exporter。

## 真实环境发现并修复的问题

首轮真实浏览器为 6/7，审计页读取后台记录时返回 500。既有普通订单超时审计包含合法的 `reservationNo: null`，而 `AuditLogResponse` 的 `Map.copyOf` 禁止空值。该逻辑在升级前快照中已经存在，并非 Boot 4 引入。改用保留空值的不可变防御性副本，不删除审计字段、不修改历史事实；增加历史字面 JSON 的数据库/HTTP 与分页回归。未知异常日志现在只记录异常类名到已有 `errorType` 字段，恢复原 MDC，不记录异常消息、堆栈或请求载荷，客户端 Problem 响应不变。

运行时 OpenAPI 比较还发现 Jackson 3 `JsonNode` 被错误展开成 Java 内部属性、Spring 7 JSpecify 可空标记未被文档工具识别。通过正式 ModelConverter 恢复任意 JSON 与 SSE timeout 的 `integer|null` 表达，并同时测试普通模型仍使用默认解析。

保留三项经逐项审阅的文档补全：quantity 的 `exclusiveMinimum: 0`、stock 的 `minimum: 0`、delay 的 `format: duration`。升级前源码已经分别使用 `@Positive`、`@PositiveOrZero`、`Duration`；没有收紧实际服务的输入规则。原始基线、初轮六处差异和精确批准的三个新增字段均归档，不将更新后的基线无漂移表述为“与原始基线没有任何变化”。

## 验证结果

最终 `verify04` 于 2026-10-01 11:28:17 UTC（北京时间 19:28:17）完成，耗时 8 分 27 秒。**437 项 Java 测试全部通过，失败、错误、跳过均为 0**。verify03 因原生 JVM 崩溃中断，单独归档，不计入成功结果。

| 模块 | 通过数 |
| --- | ---: |
| common | 85 |
| infrastructure | 5 |
| domain | 16 |
| database | 39 |
| security | 55 |
| task | 98 |
| portal | 83 |
| admin | 56 |

- **产物核对**：三个运行镜像的 `/app.jar` SHA256 均与本次完整回归产物相同；嵌入依赖确认 Rabbit 5.36.0、Boot 4.1.1、Jackson 3.1.7 / 2.21.7、MyBatis starter/autoconfigure 4.1.0，无 Boot 3 / Spring 6 残留。最终依赖证据使用 `release-reactor/packaged-dependencies.json`，旧的 `packaged-dependencies-final.json` 属于 verify02，不能代替最终结果。
- **运行检查**：Web、Portal、Admin、Task、Agent 的就绪请求均为 HTTP 200；三个 Java 服务均 UP，Prometheus 均返回 JVM 与 HTTP 指标。
- **接口契约**：相对升级前原始基线只有上文列出的三项元数据补全；精确守卫通过后，仅更新 user/admin 基线中的这三个字段。四个 Java API 域完整语义比较通过，public/user/admin 的运行时生成客户端均与现有客户端一致，`pnpm api:check` 四个客户端域检查通过。
- **真实浏览器**：原 7 项验收全部通过，耗时 37.1 秒；包括购买、模拟支付、秒杀、Agent 购买确认、库存冲突、后台审计、越权操作拒绝与知识库引用。没有减少原有断言。
- **历史数据补证**：独立只读读取现有 SYSTEM 审计，共 5 页 21 条，全部 HTTP 200、无重复 ID，保留 10 条显式 `reservationNo: null`。这项本机历史数据检查不作为版本化 E2E 的固定数据依赖。
- **工程检查**：CI 工具 50 项测试及 CI policy 通过；最终源码快照的 Gitleaks 与仓库四条 Semgrep 规则检查结果见 `security/`。这是既有 CI 范围的检查，不代表完整安全审计。

最终原始日志、JUnit、JaCoCo、环境条件、JAR 清单与文件摘要在 `release-reactor/`；源码冻结清单在 `security/source-manifest.json`。此前的 `full-reactor/`、`full-reactor-final/`、`interrupted-reactor-03/` 与 `initial-web/` 保留各自阶段的原始结果。

完整 Java 回归使用开发工具链 Temurin **21.0.11+10**，容器 4 核/3 GiB、测试 JVM 最大堆 768 MiB，并限制 C1。实际应用镜像保持原先固定摘要的 Alpine Java 21 基础镜像，运行时核实为 Temurin **21.0.12+8**，浏览器验收在这些运行镜像上完成。两种环境分别记录；通过结果只适用于本轮配置，不据此宣称原生崩溃根因已解决。

最终报告以本轮证据为准，不把前一轮 Agent 302 项、Web 114 项单测作为本轮重新运行结果。模型与支付沿用本地演示实现，未验证外部生产支付或付费模型。

## Docker 与本地运行

交付入口：<http://localhost:18081>。普通账户可在页面注册；后台演示账户见上一轮记录。继续使用原有忽略目录 `.local/keys/hotshop-modernize-0930/`，没有重新生成生产凭据或打印密钥。

三个 Java 服务与 demo 前端增加 `restart: unless-stopped`，使正常运行的容器在 Docker daemon 恢复后自动启动。显式手工 `stop` 仍表示保持停止；本轮没有为了测试而重启整台 Docker daemon。

开发容器的 Temurin 21.0.9 先后出现 C2 编译线程与主线程内存分配/JFR 路径的原生 SIGSEGV，原始记录均归档；第二次在 C1 限制下仍发生，不能宣称 C1 已解决根因。随后用户报告 Docker 崩溃并重启。恢复后仅更新容器内开发工具链至 Temurin 21.0.11，将开发容器限制为 4 核/3 GB、测试 JVM 最大堆 768 MB，并从头验证。没有改动宿主 Java；没有将 Docker 崩溃断言为某一个确定原因。

开发 Maven、demo 构建与 demo 运行仍保留 `-XX:TieredStopAtLevel=1`。Dockerfile 的 `BUILD_JAVA_TOOL_OPTIONS` 默认空，仅 demo overlay 传入该设置；常规镜像运行仍接受默认 JVM 优化。最终验证结果不等同于默认 C2 下的生产稳定性或性能认证。

三个 `hotshop-modernize-{admin,portal,task}:pre-boot4-20261001` 镜像标签是明确的应用回退点，复用原镜像层。本轮临时构建、浏览器、扫描容器使用 `--rm`；开发容器在验收后回收，Testcontainers 由 Ryuk 回收。没有全局 prune，没有清空数据卷。

## 证据与后续边界

证据保存在 `.local/verification/boot4-20261001/`；包含升级前源码快照、初轮构建、最终日志、JUnit/JaCoCo、依赖清单、运行时契约及浏览器证据。该目录被 Git 忽略，避免本机密钥、测试令牌或大型产物进入源码；旧验证结果保留原时间与版本。

本轮没有完成 Agent 跨实例事件回放、Redis Cluster、历史事件归档或生产容量认证，也不据本地交易通过宣称目标吞吐。Java 21 与 Flyway 11 的后续升级应各自验证，不与本次框架迁移混为一项结论。

迁移依据：[Boot 4 指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Boot 依赖表](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)、[MyBatis Starter](https://github.com/mybatis/spring-boot-starter/blob/master/README.md)、[springdoc 兼容表](https://springdoc.org/)、[Jackson 3.1](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1)、[Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)。
