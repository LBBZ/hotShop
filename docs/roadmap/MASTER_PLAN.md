# HotShop 改造总纲

> 项目定位：**HotShop — 智能高并发秒杀与交易平台**
> 英文副标题：**Agentic High-Concurrency Commerce Platform**

本文档是所有实现任务和验收工作的共同约束。新的 Codex 对话在开始编码前，必须完整阅读本文档及
`TASK_CATALOG.md` 中对应的任务，不得自行改动已经确认的架构。

## 1. 项目目标

将现有 Spring Boot 多模块商城改造成可在应届生全栈开发、Agent 全栈开发岗位中展示的工程项目。
项目必须能够通过源码、自动化测试、运行证据、压测报告和故障演练解释其正确性，不能只追求功能演示。

当前阶段仅要求本机运行，不考虑公网部署。项目交付形态为：

- Docker Compose 一键启动完整环境；
- GitHub Actions 持续集成；
- React 用户端与管理端；
- Java 交易核心和 Python Agent 服务；
- 可复现的 k6 压测与 Grafana 仪表盘；
- 架构决策、测试报告、压测报告和演示脚本。

## 2. 不可变架构约束

除非回到全局验收对话重新决策，否则实现任务不得改变以下约束。

### 2.1 系统边界

- 单仓库，不改造成微服务项目，不引入服务注册中心、配置中心或分布式事务框架。
- 保留清晰的 Maven 模块和可独立运行进程，但简历中称为“模块化、多进程架构”，不称为微服务。
- Java 21、Spring Boot 最新稳定的 3.5.x、MyBatis 3.0.x、Maven Wrapper。
- React + TypeScript + Vite；使用 React Router、Tailwind CSS、shadcn/ui、TanStack Query、
  Zustand、Recharts、Vitest、Testing Library、Playwright、pnpm。
- Agent 使用 Python FastAPI + LangGraph；模型通过只读 Provider Registry 选择单个活动 Provider，
  推荐真实模型为 DeepSeek，同时保留百炼 Qwen；测试和 CI 固定使用 FakeModel，不做自动跨 Provider
  fallback。ModelProvider 与 EmbeddingProvider 必须保持独立。

### 2.2 数据与消息

- MySQL 是交易数据最终事实来源。
- 所有数据库结构由 Flyway 版本化迁移管理。
- **任何表都不使用数据库外键**，也不使用级联删除；使用主键、唯一约束、非空约束、检查约束及应用层校验。
- 两个独立 Redis 实例且只使用 DB 0：
  - `redis-cache`：缓存、限流、会话，可淘汰、可重建；
  - `redis-seckill`：预约、幂等、Redis Stream，`noeviction`，AOF + RDB 持久化。
- 不保留按逻辑数据库动态生成 `RedisTemplate` 的设计。
- 秒杀入口通过 Redis Lua 原子完成活动校验、一人一单、库存预扣和 `XADD`。
- Redis Stream 消费者异步创建 MySQL 订单；Pending List、重试、认领、补偿和对账必须完整。
- 预约状态至少覆盖 `RESERVED → ORDER_CREATED` 与
  `RESERVED → COMPENSATING → COMPENSATED`；补偿必须幂等。
- RabbitMQ 仅用于后续业务事件和未支付订单超时。
- 不依赖 RabbitMQ delayed-message 插件；固定超时使用 TTL 队列 + DLX。
- 业务事务与消息使用 Transactional Outbox；消费者使用 Inbox/业务唯一约束去重。
- 对外语义是“至少一次投递 + 幂等业务效果”，不得宣称 exactly-once。

### 2.3 鉴权与 Agent

- 匿名用户只能浏览、搜索商品和查看活动。
- 下单、订单查询、支付和用户 Agent 必须登录。
- 管理端所有接口都必须具备管理员身份。
- Access JWT 短时有效；Refresh Token 为 HttpOnly/Secure/SameSite Cookie 中的随机不透明令牌。
- Refresh Token 服务端仅保存哈希，必须轮换并检测复用及令牌家族泄露。
- 用户、管理员、Agent 委托使用独立 issuer/audience；使用非对称签名。
- Agent 服务分用户端和管理端 API、工具注册表与策略。
- 用户 Agent 只能查询商品、比较商品、查询本人订单、检索 FAQ、创建购买草稿。
- Agent 写操作必须由用户确认，并使用短时、一次性的委托确认令牌。
- 管理 Agent 只开放读取、低风险分析和配置草稿；高风险后台操作永不开放给 Agent。
- 每次工具调用都重新校验身份、资源归属、scope 和参数 Schema。
- 商品描述、FAQ、检索文档和用户输入均视为不可信数据，不能改变 Agent 权限。
- Qdrant 仅存 FAQ、售后政策和活动规则；商品、库存、订单必须由实时工具查询。
- 不记录或返回模型思维链；日志和 SSE 只包含结构化阶段、工具结果摘要和最终回答。

### 2.4 API 与前端

- 普通接口使用 REST JSON；Agent 流式响应和订单进度使用 SSE，不为全站引入 WebSocket。
- 统一 Problem Details 错误格式、请求 ID、Trace ID、参数校验和 DTO，不直接暴露数据库实体。
- 写接口按场景支持 `Idempotency-Key`；列表使用稳定排序和游标分页。
- OpenAPI 是前后端契约来源，并生成 TypeScript 客户端。
- 一个 React 应用包含用户区和管理员区，路由、菜单和按钮权限不能替代后端鉴权。

### 2.5 支付、审计与可观测性

- 不接真实微信或支付宝；实现明确标注为 Mock 的支付提供方。
- Mock Payment 支持签名、时间戳、防重放，以及成功、失败、重复和延迟回调。
- 支付成功与超时关单通过数据库条件更新竞争，只允许一个最终状态生效。
- 管理操作、Agent 工具调用、支付回调、库存补偿必须写入只追加审计日志。
- 审计记录身份、动作、资源、结果、请求 ID、Trace ID 和脱敏状态摘要，不保存密码、Token、API Key、
  完整提示词或思维链。
- 使用 Prometheus/Grafana、Loki/Tempo 和 Alloy，全部运行在 Docker 中。

## 3. 质量门禁

每个任务必须满足以下通用完成定义：

1. 只修改任务范围内的文件；发现范围外问题先记录，不顺手重构。
2. 不覆盖现有未提交改动；开始和结束都提供 `git status --short`。
3. 不提交、推送、创建分支或 PR，除非用户在该任务中明确授权。
4. 不将密码、私钥、模型 Key 或 GitHub Token 写入仓库。
5. 新增行为必须有自动化测试；修复缺陷必须有能复现旧缺陷的回归测试。
6. 至少运行与本任务直接相关的测试；无法运行时给出原始错误和可复现命令，不能声称通过。
7. 更新受影响的文档、示例环境变量和运行命令。
8. 交付报告必须包含：改动摘要、关键取舍、文件清单、执行命令、测试结果、遗留风险。

最终项目的总体测试矩阵：

- Java：JUnit 5、Mockito、Testcontainers、Awaitility；
- Python：pytest、契约测试、Agent eval；
- React：Vitest、Testing Library；
- 端到端：Playwright；
- 契约：OpenAPI 兼容性与生成客户端检查；
- 消息与故障：Toxiproxy、容器重启、重复消息和乱序测试；
- 安全：依赖扫描、Secret 扫描、静态分析、ZAP 基线扫描；
- 关键状态机：属性测试或变异测试；
- 性能：k6 + 监控指标与数据库事实校验。

## 4. 性能目标的正确表述

以下是待验证目标，不是已经实现的数据：

- 本机最高负载测试目标：10,000 VUs；
- 秒杀接入接口目标吞吐：5,000 req/s；
- 秒杀接入接口 P99：不高于 200 ms；
- 异步创建数据库订单 P99：不高于 3 s；
- 超卖数为 0，重复有效订单数为 0，账实差异为 0。

只有在固定硬件、数据规模、测试脚本、持续时间、预热方式和监控证据完整后，才能把实测结果写进简历。
GitHub 托管 Runner 只用于功能持续验证，不用于产生正式性能结论。

## 5. 执行与验收流程

默认每次只让一个实现对话修改共享工作区。只有全局验收对话明确发放同一并行波次、划定文件所有权时，
才允许多个实现对话同时工作。并行任务必须遵守 `PARALLEL_EXECUTION.md`，不得编辑其他任务拥有的文件，
也不得还原、暂存、清理或提交其他对话产生的改动。

1. 全局验收对话给出一个任务编号，或明确给出可并行执行的任务波次。
2. 新实现对话完整阅读本文件和对应任务。
3. 实现对话检查现状，完成代码、测试和文档，不做任务外扩张。
4. 实现对话按统一交接模板返回结果。
5. 回到全局验收对话，由验收方分别检查每个任务拥有的 diff、运行测试并给出：
   - `通过`：进入下一个任务；
   - `有条件通过`：只剩明确的非阻断项；
   - `拒绝`：列出必须返工项。
6. 未通过验收前不得开始依赖该任务的后续任务；同波次任务也不会因另一个任务提前完成而自动扩大范围。

### 实现对话统一开场提示

复制以下文字，再附加任务编号：

```text
你是 HotShop 的实现工程师。请先完整阅读：
1. docs/roadmap/MASTER_PLAN.md
2. docs/roadmap/TASK_CATALOG.md 中指定任务

只执行指定任务，不自行改变总纲中的架构，不开始后续任务。保护工作区已有未提交修改；
先检查 git status 和相关代码，再实施、测试和更新文档。不要 commit、push 或创建 PR。
结束时必须按任务目录中的交接模板报告真实执行结果。指定任务：TASK-XX。
```

### 实现对话统一交接模板

```text
任务：
结论：完成 / 部分完成 / 阻塞

改动：
- ...

关键设计与理由：
- ...

测试证据：
- 命令：
- 结果：

文件：
- ...

未完成项或风险：
- ...

git status --short：
...
```

## 6. 当前工作区保护说明

制定本计划时已存在以下用户改动，任何任务都必须将其视为用户资产：

- 已修改：`Dockerfile`
- 已修改：`docker-compose.yml`
- 未跟踪：`.dockerignore`
- 未跟踪：`docker/rabbitmq/`

相关任务可以在理解现有内容后继续演进这些文件，但不得直接覆盖、还原或删除。
