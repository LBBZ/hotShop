# 简历与面试材料

事实核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`。以下是可选表述，只有你实际承担过并能解释的贡献才使用“实现/设计”；仓库存在代码不证明个人贡献。测试历史与当前验证分开见[证据索引](../quality/evidence-index.md)。本项目是本地 Docker 演示项目。

> [RECONCILE-01](../quality/task-21-reconcile-01.md)补齐审计开放字段兼容、未知值分页/筛选和中英文指定问题引用验证。
> 原中文改写问题仍按默认阈值安全拒答；历史检索异常根因及刷新中断仍未决。
> 不得扩大为任意中文语义召回、真实模型效果、生产容量或全项目终验主张。

## A. 全栈 / 后端并发方向

**项目简介（可直接使用）**：HotShop 是基于 Java 21、Spring Boot、React 的模块化交易演示项目，涵盖商品、普通订单、秒杀预约、模拟支付和后台审计，使用 MySQL、双 Redis 与 RabbitMQ 展示并发库存控制及异步一致性。

**贡献描述（按实际职责选用）**：

- 实现 Redis Lua 预约与 Stream 异步建单，结合 MySQL 条件扣减、处理记录、补偿和对账，处理重复消息及部分失败。
- 使用事务 Outbox、发布确认和消费者幂等，连接订单、模拟支付及超时关闭流程，支持失败追踪和受控重试。
- 分离后台元数据编辑与显式库存调整，以版本冲突、库存预期余额和审计防止旧表单覆盖库存变化。
- 建立 Java Testcontainers、前端测试、隔离浏览器演示及 k6 证据入口，区分正确性门禁和未达标的性能目标。

| 技术主张 | 当前源码 / 报告证据 | 适用条件与不能宣称 |
|---|---|---|
| 模块化全栈交易 | [根 POM](../../pom.xml)、[前端依赖](../../web/package.json)、[Compose](../../docker-compose.yml) | 多模块、多进程，共享交易数据库；不能写成微服务或线上商城运营经历。 |
| 原子预约、异步建单 | [FlashSaleReservationService](../../domain/src/main/java/com/real/domain/service/seckill/FlashSaleReservationService.java)、[ReservationStreamConsumer](../../task/src/main/java/com/real/task/seckill/ReservationStreamConsumer.java)、[SeckillProcessingService](../../task/src/main/java/com/real/task/seckill/SeckillProcessingService.java) | Lua 原子范围在同一 Redis；跨 Redis/MySQL 是异步恢复，不是跨存储原子事务。 |
| 可靠消息 | [OutboxPublisher](../../task/src/main/java/com/real/task/outbox/OutboxPublisher.java)、[OrderTimeoutConsumer](../../task/src/main/java/com/real/task/timeoutOrderTask/OrderTimeoutConsumer.java)、[真实消息测试](../../task/src/test/java/com/real/task/outbox/ReliableMessagingContainerTest.java) | 至少一次投递与业务幂等；不能写 exactly-once、永不丢消息或自动处理所有异常。 |
| 库存并发与审计 | [ProductMapper.reduceStock/increaseStock](../../domain/src/main/java/com/real/domain/mapper/ProductMapper.xml)、[AdminProductAuditService.adjustStock](../../admin/src/main/java/com/real/admin/service/AdminProductAuditService.java)、[联合回归](../../admin/src/test/java/com/real/admin/InventoryReconciliationJointContainerTest.java) | 元数据更新不写 stock；显式 delta/expectedVersion 调整。不能称完整事件溯源或可识别所有 DBA 篡改。 |
| 测试与性能分析 | [CI 与证据矩阵](../quality/evidence-index.md)、[TASK-20 正式报告](../quality/task-20-performance.md) | 只引用报告绑定的 SHA、窗口和负载语义；不把配置 5000 requested RPS 写成承载能力。 |

## B. Agent 全栈方向

**项目简介（可直接使用）**：HotShop 在 Java 交易核心与 React 用户端上集成 FastAPI/LangGraph Agent，提供商品与本人订单查询、购买草稿和静态知识引用，通过用户确认与服务端工具权限限制交易能力，默认使用 FakeModel 和确定性 Embedding 验证。

**贡献描述（按实际职责选用）**：

- 实现用户与管理员分离的工具注册表，调用前重验身份、资源归属与 scope，由 Java 服务再次执行授权和业务校验。
- 将 Agent 购买意图限制为草稿，由用户主动确认后消费绑定用户、草稿和参数摘要的短期确认值，复用交易核心建单。
- 分离 ModelProvider 与 EmbeddingProvider，保留 Fake/DeepSeek/Qwen 对话适配以及 deterministic/Bailian 向量适配，构建静态知识检索与来源引用。
- 实现流式事件、取消和模型可靠性控制，使用确定性评测、权限拒绝案例和契约测试验证边界。

| 技术主张 | 当前源码 / 报告证据 | 适用条件与不能宣称 |
|---|---|---|
| Agent 编排与权限 | [graph.py](../../agent/src/hotshop_agent/graph.py)、[ToolRegistry.execute](../../agent/src/hotshop_agent/registry.py)、[AgentTokenExchangeService](../../security/src/main/java/com/real/security/service/AgentTokenExchangeService.java) | 工具和路径由代码固定，不能说模型自动获得任意 API、SQL 或后台管理权限。 |
| 用户确认购买 | [PurchaseConfirmationService.issue/consume](../../domain/src/main/java/com/real/domain/agenttools/PurchaseConfirmationService.java)、[确认集成测试](../../portal/src/test/java/com/real/portal/agenttools/AgentToolsAndPurchaseConfirmationIntegrationTest.java) | Agent 只建草稿；不能称全自动交易、自动支付或真实资金结算。 |
| 多 Provider 适配 | [MODEL_PROVIDER_REGISTRY](../../agent/src/hotshop_agent/providers/factory.py)、[ModelProvider](../../agent/src/hotshop_agent/providers/base.py)、[container._build_embedding](../../agent/src/hotshop_agent/container.py) | 单次配置选择一个模型；没有自动跨厂商故障切换。本轮未调用付费 Provider，契约 Mock 不是厂商在线联调。 |
| 静态 RAG | [RagRetriever/route_query](../../agent/src/hotshop_agent/rag.py)、[QdrantStore/KnowledgeIndexer](../../agent/src/hotshop_agent/qdrant.py)、[知识目录](../../agent/knowledge) | 引用包含文档、版本和 chunk；实时库存/价格/订单走工具。不能把有限静态语料测评说成通用检索正确率。 |
| 流式与可靠性 | [service.py](../../agent/src/hotshop_agent/service.py)、[reliability.py](../../agent/src/hotshop_agent/reliability.py)、[半开生命周期回归](../../agent/tests/test_circuit_lifecycle.py)、[评测 runner](../../agent/src/hotshop_agent/eval_runner.py) | FakeModel 可证明编排和边界，不证明真实模型幻觉率、在线费用或真实 Provider 延迟。 |

## 可引用的性能数字

建议简历正文不写吞吐数字；面试时可以展示一次未达标探索，说明如何约束证据。正式受测代码为 `550231abfc9fde3228b307f88e1de99b5c35f3cb`，RunId `run-reconcile02-default-0915-01`：5000 requested RPS、1000 VU、10 秒到达窗口、2 秒 warmup；实际完成/接受预约 2206/2206，dropped iterations 47791。报告的 220.6 RPS 是完成量除以 10 秒，包含窗口后尾部完成，原始 k6 执行 15.8233 秒，不能表述为稳定吞吐。

预约请求 P95/P99 为 4547.55/5088.75 ms，异步订单 P95/P99 为 49785.16/52094.12 ms。每个迭代先真实登录，登录耗时占用 VU，预约延迟指标不含登录。正确性门禁通过、性能门禁未通过，细节与消息排除见[版本化报告](../quality/task-20-performance.md)。不能宣称用户规模、生产 SLA、商业收益、真实支付或线上事故处置。

## 面试问答：结合代码说明取舍

### 1. Redis 已预约成功，为什么订单还可能失败？

Lua 只保证 Redis 内的活动校验、配额预占和 Stream 写入原子完成；MySQL 建单发生在后续 worker。`ReservationStreamConsumer.process` 调用 `SeckillProcessingService.createOrder`，事务内写预占事实、扣减商品/活动库存、订单和 Outbox。数据库库存不足或其他事实冲突仍可能失败；消费者按结果恢复投影、重试、补偿或记录需人工处理。预约成功不能直接显示支付成功。源码见上表预约链路。

### 2. 为什么不能承诺 exactly-once？ACK 放在哪里？

Broker confirm 后、Outbox 标记 PUBLISHED 前进程崩溃，恢复会再次发布；消费者提交后、ACK 前崩溃也会重复消费。`OutboxPublisher.publishInternal` 等待 confirm 且检查 returned message，使用 lease/version 更新发布事实；`OrderTimeoutConsumer` 在事务处理后 ACK，并区分可重试与终止路径。Stream 通过 pending/claim 恢复，订单成功还要修复 Redis 投影后 ACK；人工终止路径先持久化证据再 ACK。最终效果依赖处理记录、业务唯一键和订单状态条件，不依赖网络只送一次。

证据：[OutboxPublisher](../../task/src/main/java/com/real/task/outbox/OutboxPublisher.java)、[OrderTimeoutConsumer](../../task/src/main/java/com/real/task/timeoutOrderTask/OrderTimeoutConsumer.java)、[ReservationStreamConsumer](../../task/src/main/java/com/real/task/seckill/ReservationStreamConsumer.java)。

### 3. 重复请求与重复消息是一回事吗？

不是。普通购买的 `IdempotentOrderCreationService` 绑定用户、请求键与参数摘要；同键不同参数是冲突。秒杀预约还有每用户活动限购和预约号。消费端 eventId 去重不能代替业务唯一性：两个 eventId 也可能指向同一 reservationNo。分页对账因此用跨页 seen hash 对预占号去重；这不是把所有不同业务请求合并。

证据：[IdempotentOrderCreationService](../../portal/src/main/java/com/real/portal/userjourney/IdempotentOrderCreationService.java)、[对账去重红绿测试](../quality/review-fixes-2026-09-15/dedup-design.md)。

### 4. 后台编辑旧表单会不会把订单扣减的库存加回来？

元数据更新 SQL 只写名称、价格、分类和描述。库存调整独立提交 delta 和 expectedVersion；`adjustStock` 在行锁下比对版本并执行带版本条件的 SQL，冲突要求刷新。普通扣减/回补同步递增版本，并维护 expected_stock。成功审计与业务处于事务中，审计失败必须回滚；这比“编辑时加锁但仍覆盖旧 stock”多了明确的并发语义。

证据：[AdminProductAuditService](../../admin/src/main/java/com/real/admin/service/AdminProductAuditService.java)、[AdminProductMutationRepository](../../domain/src/main/java/com/real/domain/adminops/AdminProductMutationRepository.java)、[InventoryEditContainerTest](../../task/src/test/java/com/real/task/seckill/InventoryEditContainerTest.java)。

### 5. expected stock 能证明从项目诞生以来都没有丢库存吗？

不能。V1.9 在迁移时将实际库存设为预期余额，随后合法增减同步维护 actual/expected。它能检测迁移基准之后只改实际值的偏差；无法追溯迁移前差异，也无法识别同时篡改两列的特权写入。商品余额与活动配额分别维护，避免普通下单被错误计成秒杀守恒异常。

证据：[V1.9 迁移](../../database/src/main/resources/db/migration/V1_9__inventory_accounting_and_adjustments.sql)、[SeckillReconciliationService](../../task/src/main/java/com/real/task/seckill/SeckillReconciliationService.java)。

### 6. 有界对账是不是一定能在持续写入时完成？

不是。`runBatch` 对 Stream 页和 Redis 查询设预算，并持久化活动游标、checkpoint、fence 和 seen 状态；写入 fence 改变时重启该活动核算，避免把不同时间的页拼成伪快照。持续写入可能使完整核算一直 IN_PROGRESS，其他活动和逐事件检查仍推进。seen 的总空间随本轮唯一预占数增长，单次读取有界不等于总空间常数。

证据：[SeckillReconciliationService](../../task/src/main/java/com/real/task/seckill/SeckillReconciliationService.java)、[独立复核补修限制](../quality/independent-review-fixes-2026-09-15.md)。

### 7. 为什么不用数据库外键，如何保护数据完整性？

这是当前项目明确选择，并不意味着外键没有价值。Flyway 用主键、唯一、非空、CHECK 和索引约束单表事实，应用在事务中校验关联及所有权，商品采用软删除保留订单关联；对账负责发现跨存储偏差。代价是应用与维护脚本必须遵守相同协议，直接 SQL 可以制造孤儿数据，不能声称数据库自动保证所有引用完整性。

证据：[迁移目录](../../database/src/main/resources/db/migration)、[OrderStateService](../../domain/src/main/java/com/real/domain/service/advance/OrderStateService.java)、[ProductMapper.delete](../../domain/src/main/java/com/real/domain/mapper/ProductMapper.xml)、[数据库说明](../architecture/database-schema.md)。

### 8. 支付成功和超时关闭同时发生怎么办？

`PaymentService`、`MockPaymentCallbackService` 与 `OrderStateService` 通过数据库锁和状态条件决定终态；晚到的成功回调不能把已关闭订单直接恢复为成功订单，而要保留迟到结果与审计。秒杀关闭还需要幂等库存回补和 Redis 投影恢复。Provider 是 `MockPaymentProvider`，这里只验证模拟回调和状态竞争，没有连接真实支付通道。

证据：[MockPaymentCallbackService](../../portal/src/main/java/com/real/portal/payment/MockPaymentCallbackService.java)、[终态竞争测试](../../portal/src/test/java/com/real/portal/PaymentTerminalRaceContainerTest.java)、[秒杀超时测试](../../task/src/test/java/com/real/task/payment/SeckillPaymentExpiredDeliveryContainerTest.java)。

### 9. Refresh Token 为什么不用长期 JWT？

Access JWT 用于短期 API 鉴权；刷新值是随机不透明令牌，通过 Cookie 传输，数据库只保存哈希。`RefreshSessionService.rotate` 锁定记录，检查 CSRF、身份域和用户状态，旧值变 ROTATED 并产生后继；再次使用已轮换值撤销 ACTIVE 家族成员。浏览器需要协调刷新，不能把合法并发复用当无害行为。Cookie Secure 的本地 HTTP 例外必须按环境明确配置。

证据：[RefreshSessionService](../../security/src/main/java/com/real/security/service/RefreshSessionService.java)、[RefreshCookieService](../../security/src/main/java/com/real/security/service/RefreshCookieService.java)、[认证 runbook](../runbooks/authentication-operations.md)。

### 10. 为什么模型不能绕过用户确认？

Token Exchange 只允许有限 scope。Agent 的 USER registry 只有查询、比较与 `create_purchase_draft`，没有确认签发/消费工具；Java `PurchaseConfirmationService` 校验真实用户、草稿状态、有效期和参数摘要，并持久化消费结果用于重放。用户按钮走用户 API，不能以一句模型“用户已同意”替代该授权链。管理员 registry 也不开放退款、补偿、Outbox replay、权限或密钥工具。

证据：[Registry](../../agent/src/hotshop_agent/registry.py)、[确认服务](../../domain/src/main/java/com/real/domain/agenttools/PurchaseConfirmationService.java)、[权限集成回归](../../portal/src/test/java/com/real/portal/agenttools/AgentToolsAndPurchaseConfirmationIntegrationTest.java)。

### 11. 为什么用 SSE？重连就能恢复所有对话吗？

页面主要接收单向事件，SSE 比双向协议简单。交易 SSE 从持久化 timeline 按事件 ID 续读并校验 owner，当前实现每 2 秒轮询、15 秒心跳、每进程最多 200 连接；这不是已压测出的容量。Agent 则有自己的 session/run、sequence 和取消生命周期，不能把交易的持久化重放能力直接套在 Agent 上。UI 需要绑定 run/session 并丢弃迟到事件。

证据：[TransactionEventStreamService](../../portal/src/main/java/com/real/portal/sse/TransactionEventStreamService.java)、[Agent service](../../agent/src/hotshop_agent/service.py)、[Agent state](../../agent/src/hotshop_agent/state.py)。

### 12. 换 Qwen 会改变知识向量吗？

对话模型与向量 Provider 是独立配置，换对话模型不会自动更换 Embedding。工厂只从不可变 registry 选择 fake/deepseek/qwen；`_build_embedding` 选择 deterministic/bailian。向量模型或维度变化需要按索引流程重建并验证，不可假定向量空间兼容。当前没有自动跨 Provider fallback。

证据：[模型工厂](../../agent/src/hotshop_agent/providers/factory.py)、[容器装配](../../agent/src/hotshop_agent/container.py)、[索引 runbook](../runbooks/agent-rag.md)。

### 13. RAG 引用和实时事实如何区分？

`route_query` 将售后/规则/FAQ 路由到静态检索，将价格、库存、本人订单/预约路由到固定工具；不明确的动态问题可拒绝。`RagRetriever` 按可信 tenant、身份可见性、文档类型和生效期过滤，低分或不可用时返回空/不可用结果，引用带 documentId/title/version/source/chunkId。确定性路由和有限评测集都有覆盖边界，不保证任意自然语言都分类正确。

证据：[rag.py](../../agent/src/hotshop_agent/rag.py)、[知识评测](../../agent/evals/task17-v2.jsonl)、[eval_runner](../../agent/src/hotshop_agent/eval_runner.py)。

### 14. CI 全绿为何不等于交付全部完成？

基线 CI 核实的是 Java、非 Qdrant Agent、quick 评测、Web 单元测试和 mocked smoke、契约、镜像构建与静态策略。它没有执行独立 full workflow 的真实浏览器与故障矩阵，更没有长期稳态压测。历史 TASK-19 与 TASK-20 结果属于不同版本，不能把测试数相加。真实模型质量还需要受控在线评测，本轮没有调用付费模型。

证据：[逐 job 核验和证据分级](../quality/evidence-index.md)。

### 可选增量贡献与追问：如何修复跨模块审计契约漂移？

可用表述：清点跨模块审计写入，统一共享动作与资源类型；读取保留未知历史代码，补充真实MySQL的
分页边界、精确筛选及浏览器回归。Agent检索增加不含正文的请求/运行关联诊断，并验证向量库断连降级与恢复。

实际取舍：写入用enum约束当前合法代码，读取开放action/resource字符串，避免未来值令`limit+1`映射整页失败。
数据库封闭CHECK字段保持同步契约；原记录不删除。客户端从封闭enum改为string，旧导出类型使用方需要更新。
只能宣称报告中执行过的有限范围，不能宣称历史瞬时异常根因已修复或任意未知数据库破坏都可恢复。
证据：[写入清点及源码](../quality/task21-reconcile-audit-inventory.md)、[红绿与复验](../quality/task-21-reconcile-01.md)。
