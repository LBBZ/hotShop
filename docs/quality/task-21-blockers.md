# TASK-21 演示阻塞与待调查边界

> 历史失败证据，正文不追改。RECONCILE-01已修复审计开放字段的读取契约，补中文直接问题引用、
> 原改写拒答与运行中Qdrant断连/恢复验证；原瞬时检索异常根因、刷新中断边界仍未决。
> 当前结果见[修复与复验报告](task-21-reconcile-01.md)。

基线：`10d82528aab71766ab5aa6020180ae4935f96359`。本轮仅记录，不修改业务。真实操作由 TASK-21 主 agent 在本机独立 Compose project `hotshop-task21-demo091602` 执行；浏览器、命令和产物位置由[交付报告](task-21-delivery.md)统一登记。本文将主 agent 的运行观察、独立源码核对和未确定的推断分开，不以历史 CI 通过覆盖本次失败。

## DELIVERY-01 / 高优先级：Agent 审计记录使后台审计查询失败

### 已观察结果

完成用户 Agent 工具调用与购买确认后，后台审计 GET 返回 HTTP 500。主 agent 对隔离数据库作只读核对，`audit_log` 已存在 `AGENT_TOOL_INVOKED`、`PURCHASE_CONFIRMATION_ISSUED`、`PURCHASE_CONFIRMATION_CONSUMED`，资源类型包含 `PURCHASE_DRAFT`、`PURCHASE_CONFIRMATION`。这不是“审计没有写入”：写入已经发生，读取契约不兼容。

### 源码确认的根因

| 位置 | 事实 |
|---|---|
| [AdminAuditLogQueryService.mapRow](../../admin/src/main/java/com/real/admin/service/AdminAuditLogQueryService.java)，118–119行 | 对每条记录执行 `AuditAction.valueOf(action)` 与 `AuditResourceType.valueOf(resource_type)`，没有未知值处理。查询会先映射全部 `limit + 1` 行再分页。 |
| [AuditAction](../../common/src/main/java/com/real/common/audit/AuditAction.java) | 缺少上述三个 action，也缺少源码已经写入的 `PURCHASE_CONFIRMATION_REVOKED`。 |
| [AuditResourceType](../../common/src/main/java/com/real/common/audit/AuditResourceType.java) | 缺少 `PURCHASE_DRAFT`、`PURCHASE_CONFIRMATION`，以及后台工具使用的 `AGENT_TOOL`、`AGENT_CONFIGURATION_DRAFT`。 |
| [AgentToolAuditWriter.appendAgentTool / append](../../domain/src/main/java/com/real/domain/agenttools/AgentToolAuditWriter.java)，42、119行 | 用字符串 `AGENT_TOOL_INVOKED` 直接 INSERT `audit_log`，未经过共享 action 枚举。 |
| [AgentToolService](../../domain/src/main/java/com/real/domain/agenttools/AgentToolService.java)，208行 | 购买草稿审计资源是 `PURCHASE_DRAFT`。 |
| [PurchaseConfirmationService.issue / consume / revoke](../../domain/src/main/java/com/real/domain/agenttools/PurchaseConfirmationService.java)，112、194、235行 | 分别写 ISSUED / CONSUMED / REVOKED；签发、撤销使用 `PURCHASE_CONFIRMATION`，消费使用既有 `SALES_ORDER`。不能把消费记录的资源误记为确认对象。 |
| [AdminAgentToolAuditService](../../admin/src/main/java/com/real/admin/agenttools/AdminAgentToolAuditService.java)、[AdminAgentToolService](../../admin/src/main/java/com/real/admin/agenttools/AdminAgentToolService.java) | 管理员 Agent 同样直接写 action 字符串；资源常量为 `AGENT_TOOL`、`AGENT_CONFIGURATION_DRAFT`。这是静态确认的同类覆盖面，本次不声称已逐个操作复现。 |

Java `Enum.valueOf` 遇到未定义值会抛异常，所以只要查询窗口包含这些合法写入的记录，当前映射链不能正常返回。字段 `AGENT_API`、actor `AGENT` 在对应枚举中存在，不应误诊为它们缺失。不能通过先展示空审计页、过滤掉 Agent 记录或删除记录来宣称问题已解决。

### 影响与下轮验收

影响后台跨模块审计读取及 TASK-21 审计演示验收；没有证据表明这次故障使已提交订单回滚或审计记录丢失。修复应统一所有写入方与共享响应契约，评估未知/未来值策略并更新 OpenAPI、生成客户端与前端显示，不只补本次命中的三个字符串。

验收要求：真实 MySQL 下分别完成用户工具、草稿、签发、消费、撤销以及管理员允许/拒绝工具操作，再查询未过滤的后台审计及分页/筛选。HTTP 200，所有记录字段可解释且无遗漏；补充能在当前基线上失败的跨模块回归与真实浏览器验证。不得移除历史审计数据或弱化权限来让测试通过。

## DELIVERY-02 / 中优先级：中文确定性检索未命中，另有一次运行中检索异常

### 两组不同的观察

1. **运行中 API / 浏览器**：中文“售后退换申请应该怎么做？”触发 `rag.completed` 后返回“静态知识服务暂时不可用；暂无可靠资料，无法确认。”。只读容器日志核对到 `2026-09-15T17:44:58.671717+00:00` 的 `agent.rag.retrieval`，`outcome=unavailable`、`errorType=RetrievalError`。`rag.completed` 只是检索尝试结束事件，并非成功命中证据。
2. **另起进程的诊断**：主 agent 确认 Qdrant healthy、索引5点；同用户独立执行 `QdrantStore.search` 得到中文候选分数 `0.062578276`，低于默认阈值 `0.15`；独立 `build_container` 的 retriever 返回 `empty`。这证明该诊断进程的检索路径能返回候选，但低分过滤后未命中，不能证明运行中的 API 正常，也不能解释第一组 `unavailable` 的异常根因。

### 后续真实浏览器复验（主 agent 提供）

`delivery-browser-03.log` 中六项拆分场景为 **4 passed / 2 failed，49.0秒**：核心交易/模拟支付/秒杀/Agent确认通过；后台元数据编辑/库存409通过；管理员Agent拒绝通过；英文静态问题“How does the after-sales return policy work?”的引用通过；审计列表与中文引用失败。

因此运行中 RAG 并非全部不可用，核心引用功能已有英文真实浏览器证据。中文默认确定性检索未命中的结论仅限已测问题；后次中文页面无可靠资料，具体 `empty/unavailable` 仍应以该次事件记录核对，不能仅由页面概括混为同一错误。第一次运行的 `RetrievalError/unavailable` 与独立诊断的低分 `empty` 分别保留。

### 静态确认与未确定事项

- [config.Settings.rag_minimum_score](../../agent/src/hotshop_agent/config.py)，82行：默认阈值0.15。
- [RagRetriever.retrieve](../../agent/src/hotshop_agent/rag.py)，101–125行：先设 `unavailable`，查询后按分数过滤；成功执行但无合格项是 `empty`；`EmbeddingError` 和广义 `Exception` 捕获可以保留 `unavailable`，日志只给安全的 `RetrievalError` 分类，没有具体异常类型或调用栈。
- [AgentService](../../agent/src/hotshop_agent/service.py)，340–372行：任何检索结果均发 `rag.completed`；仅 `hit` 填充证据，`unavailable` 与 `empty` 使用不同固定回复。
- [QdrantStore.search](../../agent/src/hotshop_agent/qdrant.py)，66–115行：请求失败与响应解析失败都可能转为 `QdrantUnavailable`。健康端点正常并不证明某次带过滤条件的检索请求成功。

当前未确定运行中 API 是连接/超时、返回解析、客户端生命周期还是其他异常。不能把另起进程成功、降低阈值、替换测试问句或改文档当成这一故障的根因修复。

### 下轮范围与验收

在保留隐私的前提下增加受控诊断，区分安全异常类别并绑定 request/run，复现运行中服务与独立进程差异；另行评估确定性 Embedding 对中英文静态语料的命中边界。英文真实浏览器已得到引用；后续验收应保留该通过结果，并补中文问题的预期命中或明确拒答边界，核对文档/version/chunk，同时覆盖低分拒答与真实 Qdrant 断连降级。保持 FakeModel/deterministic 默认，不通过调用付费模型回避根因。

## DELIVERY-03 / 中优先级：页面导航中断 refresh 的会话恢复边界

### 已观察结果与调整

主 agent 的浏览器验证在 `reload` 后立即 `goto`，中断正在进行的 POST refresh，代理记录499；后续旧刷新值收到401，并产生 `REFRESH_TOKEN_REUSE_DETECTED`。演示测试改为等待工作台会话恢复完成后再继续导航，随后核心链路通过。这个等待是演示同步条件，不是产品故障修复，也没有证明任意导航/网络中断下都能无感恢复。

### 源码边界

- [auth-domain.ts](../../web/src/auth/auth-domain.ts)，74、94、114–125行：`refreshInFlight` 合并同一个 JavaScript auth-domain 实例内的刷新；刷新 POST 依赖 Cookie，成功后设置内存 session。整页导航创建新实例，这个 Promise 不能跨页面生命周期协调。
- [RefreshSessionService.rotate](../../security/src/main/java/com/real/security/service/RefreshSessionService.java)，93–110、134行：已使用刷新值标记 ROTATED；再使用旧值会撤销该家族中的 ACTIVE 令牌并记录复用。请求在客户端被中断不代表数据库事务必定取消。

“服务端轮换已提交而新 Cookie 未被浏览器接受，下一页重试旧值”是与观察及源码一致的解释；本轮没有抓取完整 Cookie/事务时序来证明每个时间点，不能作更强断言，也不应输出原始令牌作为证据。

### 下轮验收

增加真实浏览器重载/快速导航/响应丢失与多标签场景，明确安全恢复策略与用户重新登录提示；保留真正令牌复用检测，不能直接取消家族撤销来掩盖竞态。当前建议演示等工作台恢复后再继续；触发失效时重新登录，不把重复刷新旧令牌当可靠重试方案。

## 对交付与简历的约束

本轮已确认业务源码中的审计读取缺陷；英文RAG引用真实浏览器通过，中文默认确定性引用失败，另一次运行中检索异常根因仍待定位；刷新中断的强健性未完成验证。README、演示和简历只能表述已验证的具体范围；不得宣称当前混合业务审计完整可用、中英文引用均通过或所有刷新竞争已解决。后续优先顺序：审计读取一致性 → 运行中RAG异常定位及中文确定性召回边界 → 刷新中断恢复。TASK-21 不扩展为新一轮业务修复。
