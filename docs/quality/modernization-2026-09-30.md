# 2026-09-30 优化与升级记录

> 历史快照：本文保留原任务或日期对应的实现与验证事实；版本、分支、工作树路径和测试计数不代表当前状态。当前使用方式见[文档中心](../README.md)。原始失败与限制保留供追溯。

分支：`codex/modernize-hotshop`。代码读写、依赖安装、编译、测试与镜像构建均在 Docker 中执行，宿主仅调用 Docker CLI。原有 `hotshop-task21-accept0916r01` 环境与原有数据卷没有迁移或清空。

## 已实现

- 认证：V1_11 引入 MySQL 认证记录。JWT 撤销和客户端断言去重使用独立提交、哈希键及数据库唯一约束，覆盖 JWT 时钟容差；数据库不可用时认证失败，缓存丢失不能恢复撤销令牌。后台每分钟最多清理 10,000 条过期记录，可通过 `hotshop.security.marker-cleanup-interval` 调整周期。
- 交易后台：消费、Outbox、对账、指标使用独立单线程调度器，避免阻塞互相传播且保持各任务内部顺序。Outbox 每次领取一条立即发布，发送前检查确认窗口；每轮固定可领取时间界限，避免慢发布使本轮失败消息等待期到期后再次被领取。耗尽重试次数的记录转为失败后继续处理后续候选，空队列立即停止查询。Stream lag 从逐轮范围扫描改为低频 XINFO GROUPS；未知采样单独暴露指标并告警。
- 对账：默认每 30 秒最多遍历 10 个活动，主事件扫描共用 100 条预算，守恒扫描与 Pending 扫描各自按同一分页分配控制在 100 条以内；另有原有的 MySQL 反查预算。活动页之间有 2 秒软预算（不承诺单次依赖调用硬中断）。已有游标、fencing 与幂等语义保留。
- 前端：按身份隔离查询缓存，阻止旧请求/刷新覆盖新身份；修复 Agent SSE 截断、合并分帧与取消竞态；复用会话；交易 SSE 驱动刷新并在终态停止轮询。Nginx 为指纹静态文件配置 immutable 缓存及 gzip，HTML 重验证。
- Agent：索引版本包含 embedding 指纹，检索过滤不兼容向量；引用对应实际提供给模型的证据；保存有界会话历史，原子认领运行并处理重复请求、会话并发和取消。
- 部署：改用按项目操作的 Compose v2 脚本，移除全局 prune 和宿主 Maven。生产配置统一数据库名、要求密码、默认验证 TLS；本地端口默认绑定 loopback。C1 编译器限制仅保留在演示 overlay，常规镜像允许默认 JVM 优化。

## 基础设施版本

| 组件 | 原版本 | 本次版本 | 验证依据 |
| --- | --- | --- | --- |
| MySQL | 8.0.46 | 8.4.11 LTS | 官方 Docker 标签存在；8.4.12 发布说明存在但官方 Docker 标签暂不可拉取 |
| Redis | 8.8.1 | 8.8.3 | 官方 Docker 标签存在 |
| RabbitMQ | 4.2.9 | 4.3.6 | 官方镜像 digest 固定；项目镜像构建通过 |
| Qdrant | 1.15.4 | 1.19.1 | 官方 Docker 标签存在 |

前端更新为 React 19.3.0、Vite 8.3.1、TypeScript 6.0.3、Vitest 4.1.11；TypeScript 版本受当前 typescript-eslint 支持范围约束，没有强行升级到不兼容的主版本。Agent 的 LangGraph 从 0.4.8 升至 1.2.12，其余运行时锁定项保持。

Java 端 Flyway 统一为 11.20.3，与实际部署 migrator 一致。

## 验证记录

- Agent：302 项测试通过、零跳过，包含真实 Redis/Qdrant 和 6 项运行时容器安全测试；Ruff、mypy、pip check 通过。FakeModel/DeterministicEmbedding 的 quick eval 为 28/28，真实 Qdrant full eval 为 29/29，未调用付费模型。
- CI 工具：50 项测试通过；CI policy、actionlint、Prometheus 告警回归通过。固定 Semgrep 4 条规则扫描 481 个源码文件，零发现；最终 Gitleaks 对 880 个非忽略源码/配置文件扫描零发现，保留逐文件 SHA256 manifest。不能据此宣称完整安全审计通过。
- 前端：114/114 单测通过；lint、typecheck、format、build、四域 OpenAPI 无漂移检查通过；pnpm audit 全部级别为 0。两 worker 覆盖率检查通过，总行覆盖率为 25.74%（包含生成客户端，不能视为完整业务覆盖）。
- 浏览器：真实 Compose 整栈 7/7，桌面及手机 smoke 8/8；最终 task 镜像替换后，再单独复验购买、支付回调、预约成单和 Agent 确认，1/1 通过。支付和模型使用本地演示实现，没有验证外部生产支付/模型服务。生产入口 gzip 约 101.76 kB。
- MySQL：8.0.46 一致性逻辑备份恢复到全新 8.4.11，再迁移至 1.11，23/23 检查通过；比对 23 张表、20 条合成记录，包含中文、emoji、金额和已支付订单。此结果不等同于生产数据升级、原地升级或复制切换认证。
- Java：完整 Maven reactor `verify` 通过，共 338/338，失败 0、错误 0、跳过 0；common 12、domain 16、database 39、security 38、task 97、portal 82、admin 54。43 份 JUnit XML 与各模块 JaCoCo 报告已归档；实际覆盖项目源码 4,141 行，其中 task 2,158 行。最终日志没有 forced-exit 或 Flyway 版本警告。

Java 回归发现并修复了同轮 Outbox 领取重复重试的边界，新增推进时钟测试，23 项 Outbox 单测与 23 项真实消息集成测试通过。逐条租约会增加领取事务数，生产吞吐仍需压测。首次 Java 运行还暴露了测试夹具大量短连接造成的连接超时，以及 JaCoCo 对第三方框架插桩引发的退出锁竞争。夹具改用有界 Hikari 连接池，JaCoCo 仅插桩项目 `com/real/**`，仍检查项目覆盖数据。原失败日志与退出线程栈保留供复查。

浏览器首次使用 HTTP `host.docker.internal` 缺少安全上下文，改为测试容器内 localhost 代理后完成验收。首轮 localhost 验收曾有一次 Agent 后续请求瞬态失败，未捕获准确 HTTP 状态，原因没有最终确定；同用例单独重跑、整套 7 项重跑，以及经 nginx 同会话连续 6 轮请求均通过。没有降低断言或延长超时掩盖失败。

Agent 原始证据：`target/modernization-20260930/agent/verification.json` 和 `junit.xml`。

## 当前可访问环境

交付 Compose 项目：`hotshop-modernize-0930`，页面地址：<http://localhost:18081>。普通用户可在页面注册。后台演示账户为已有公开测试夹具 `task13-admin` / `Task13Admin!2026`，仅用于本机演示。数据库、缓存和服务间密钥使用本轮独立随机值，保存在忽略目录 `.local/keys/hotshop-modernize-0930/`。

宿主可使用以下 Docker CLI 管理这一个项目（工作目录为仓库根目录）：

[本地运行入口](../delivery/demo.md)


## 升级现有数据的顺序

1. 本次验收使用全新 Compose 项目、密钥与数据卷。不要把旧 MySQL 数据卷直接交给新镜像试运行。已有实例先做一致性备份，在新项目完成恢复演练，再计划切换；保留旧实例和备份作为回退点。
2. 先运行 Flyway migrator，确认到达 `1.11`，并授予 portal/admin 新表的 SELECT/INSERT/UPDATE、task 的 DELETE 权限，再启动新版 Java 服务。V1_11 是新增表，不修改旧迁移校验和。
3. 旧版 Redis 撤销/断言记录不会自动搬进 MySQL。已有环境切换前需要阻止旧 token 再进入新版服务：停止旧实例签发，等待所有旧 access/delegation/assertion 最大 TTL 加时钟容差后再切换，或按现有密钥轮换流程使旧 access/delegation 验证密钥失效并等待旧 assertion 到期。不能在仍接受旧凭据的情况下把空认证表当成完整撤销记录。
4. Agent 更新后运行 `python -m hotshop_agent.index_cli rebuild`。embedding 模型、维度或语义配置变更必须重建索引；新查询不会静默混用旧向量。
5. 回退应用时保留新增表、旧数据库实例和旧索引，不在切换过程中删除数据卷。

生产 `application-prod.yml` 支持 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`DB_SSL_MODE`。默认 `DB_SSL_MODE=VERIFY_IDENTITY`，需要提供受信任的数据库证书；Compose 演示使用其显式本地数据源配置。

## Docker 资源归属

一套完整演示包含前端、三个 Java 服务、Agent、MySQL、两个 Redis、RabbitMQ、Qdrant，共 10 个常驻服务；数据库迁移和密钥初始化是两个成功后正常退出的一次性容器。它们属于同一个 Compose 项目，不应仅因退出状态就判为垃圾。历史停止容器主要来自每批独立的集成测试/演示项目；失败现场被保留后又开启下一批，导致积累。本次先清除了 62 个历史停止容器、90 个旧镜像标签及 12 个悬空镜像，镜像统计占用减少约 6.47 GB，原有 100 个数据卷完整保留。孤立 Qdrant 容器因数据在可写层，明确保留。

本轮开发工具和临时验证资源使用 `hotshop-modernize` 前缀；手工工具容器附有 `com.hotshop.task=modernize-20260930` 标签，Compose 自带项目标签，Testcontainers 使用 Ryuk 自动回收。验证完成后清理本轮四个手工工具容器与专用 Agent 安全测试镜像，Testcontainers 临时实例已自动回收。保留一个本轮交付项目、原有环境、必要构建缓存和报告。不要用 `docker system prune -a --volumes` 代替归属清理。

报告与构建日志：`target/modernization-20260930/`（忽略目录）；清理汇总：`target/modernization-20260930/cleanup-summary.json`；本轮收尾清理：`final-cleanup.json`；总验收：`delivery-summary.json`。验证资料另外备份至 `.local/verification/modernization-20260930/`，避免 Maven `clean` 删除根 `target` 时丢失跨模块记录。

## 本轮不宣称完成的架构迁移

Java 21 继续保留。本报告交付时 Spring Boot 4 / Jackson 3 / MyBatis Spring Boot Starter 4 迁移尚未完成；后续实现与验证单独记录在 [2026-10-01 升级记录](boot4-upgrade-2026-10-01.md)。不能把本报告当时的 Boot 3.5.16 标记为仍有社区支持。Agent 跨实例事件回放、全量原生 tool-calling、Redis Cluster 分片、历史事件归档和生产容量认证也需要独立设计与实测。本次没有修改秒杀 key 的 hash tag、无条件裁剪 Stream 或宣称达到历史压测的目标吞吐。

参考：[Spring Boot 3.5 最后一个社区版本](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)、[Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[MySQL 8.4 发布说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/)、[RabbitMQ 支持周期](https://www.rabbitmq.com/release-information)。
