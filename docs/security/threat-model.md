# HotShop threat model

本威胁模型覆盖 TASK-19 的浏览器、Java 交易核心、Python Agent、Mock Provider、数据库和消息系统。
它采用“身份与数据流经过哪里，哪里重新验证”的边界，而不把容器网络本身当成授权机制。证据链接指向
可复现测试或源码；扫描结果属于 `target/task19-security/` 的本轮机器报告，不提交大报告。

## 资产与信任边界

| 边界 | 受保护资产 | 进入边界所需身份 | 不信任的输入 |
|---|---|---|---|
| 匿名浏览器 → Portal | 商品公开事实、登录入口 | 无 | URL、查询、表单、Header |
| User 浏览器 → Portal | 本人订单、预约、购买草稿、确认 | User Access；刷新仅用受限 Cookie | DOM、SSE cursor、幂等键、确认参数 |
| Administrator 浏览器 → Admin | 统计、异常、低风险运维操作和审计 | Administrator Access/Refresh | 原因、筛选、资源 ID |
| User/Admin 浏览器 → Agent | Session、Message、Run、结构化 SSE | 对应 audience/type 的 Access Token | Prompt、模型输出、SSE 字节流 |
| Agent Service Identity → Java token exchange | 短期委托身份、固定 scope、用户/管理员主体绑定 | 固定 service issuer/audience/typ/kid/alg 的签名断言 | assertion、requested scope、委托主体与 token use |
| Agent → Portal/Admin | 固定实时工具 | User Agent Delegation 或 Administrator Access | 模型工具名/参数、RAG 文本 |
| Portal/Task/Admin → MySQL | 交易、身份、Outbox/Inbox、审计 | Service 配置和最小数据库账户 | 并发事务、重复交付、历史脏数据 |
| Portal/Task → Redis/RabbitMQ | 缓存、秒杀预留、Stream、可靠消息 | 服务凭证 | 重放、乱序、重复、网络分区 |
| Mock Provider → Portal | 仅测试支付回调 | 固定 HMAC、nonce 和时窗 | 回调体、重复/迟到/篡改请求 |
| Agent → Qdrant | 版本化静态知识 | 固定 collection/alias 和代码构造 filter | 文档正文、相似度结果、注入指令 |

Access Token 只存在现有浏览器内存 Auth Store。Refresh Token 只存在 `HttpOnly`、限定 Path、SameSite、
按配置 Secure 的 Cookie。浏览器侧的 Agent Session/Run ID 只保存在 React 组件内存，不进入 Web
Storage；服务端 Session/Run 记录按 TTL 存入配置的 Agent state backend（Compose 为 `redis-cache`）。确认
明文只在点击处理函数局部变量中从签发传到消费，Java 只持久化 SHA-256 hash。浏览器存储、DOM、日志、
Trace、审计和 SSE 都不是 Secret 存储位置。

## 威胁、缓解与证据

| 威胁 | 主要攻击路径 | 缓解措施 | 可复现证据 | 剩余风险 |
|---|---|---|---|---|
| 身份冒用 | 伪造 User/Admin/Agent/Service JWT | 固定 RS256、issuer/audience/typ/kid/alg、时间窗、token use、authorized party；Java 与 Agent 各自复验 | `IdentitySecurityTest`、`AdminIdentitySecurityTest`、`agent/tests/test_security.py` | 私钥或构建供应链被攻破仍需轮换与外部检测 |
| 水平越权 | 猜测订单、预约、Agent Session/Run/Draft ID | 从 verified principal 派生 owner；资源查询后二次 owner 校验；User/Admin registry 分离 | `FlashSaleReservationIntegrationTest`、`IdentitySecurityTest`、TASK-19 双浏览器 real spec、Agent security/reliability tests | 业务新增资源必须继续采用相同 owner 模式 |
| 消息篡改 | Redis/Rabbit payload、支付回调被修改 | 严格 schema、业务唯一约束、Inbox、HMAC/nonce/时窗、非法状态转移拒绝 | `ReliableMessagingContainerTest`、`MockPaymentIntegrationTest` | 内部总线不提供端到端签名；依赖网络和凭证隔离 |
| 重放 | Idempotency-Key、回调、Stream delivery、确认 nonce | 幂等意图、Inbox/ledger/唯一键、状态机、确认 hash + `FOR UPDATE` + 一次性条件更新 | `PaymentTerminalRaceContainerTest`、`SeckillOrderReliabilityContainerTest`、确认集成测试及 real spec | 幂等保留期外的业务级重复仍需用户和对账识别 |
| CSRF/Cookie 窃取 | 跨站刷新、宽 Path Cookie、非 TLS Cookie | Refresh endpoint 的 Origin/CSRF 约束；HttpOnly/SameSite/Path；生产 Secure | `RefreshCookieServiceTest`、身份集成测试 | 部署代理必须正确传递 scheme/origin；XSS 仍会利用内存 Access Token |
| Prompt injection | 用户 Prompt 或恶意 RAG 要求改 scope/调用危险工具 | server-owned route；不可变 registry；RAG 分支禁止工具；每 run 最多一个固定工具；高风险 Admin 固定拒绝 | `test_knowledge_rag.py`、`test_registry.py`、TASK-19 Admin real spec | 模型可能产生无害但错误文本，需 citation 与人工判断 |
| SSRF / 任意执行 | 模型输出 URL、SQL、Shell、动态工具名 | 无动态 URL/SQL/Shell执行器；工具含固定 method/path；严格 Pydantic 输入；静态 Semgrep 规则 | `test_registry.py`、`.semgrep/task19.yml`、源码审查 | 未来新增 connector 需要单独 allowlist 与 DNS/rebinding 防护 |
| 敏感信息泄露 | Problem Details、日志、Trace、审计、DOM、artifact | Header/异常/Prompt 不落日志；SSE allowlist；严格事件 guard；报告脱敏；Playwright TASK-19 禁用 trace/video/screenshot | `AuditSensitiveDataSanitizerTest`、`test_streaming_sanitizer.py`、`agent-stream.test.ts`、Gitleaks | 第三方基础镜像和平台日志仍需平台侧保留期/访问控制 |
| 拒绝服务 | 超长 Prompt、SSE 不结束、工具集合放大、依赖中断 | 长度/批量/单 run 工具限制；超时；AbortController；路由卸载取消；核心交易不依赖 Agent/Qdrant | `test_cancellation.py`、`test_reliability.py`、TASK-19 outage real spec | TASK-19 不给出容量结论；容量与限流压测属于后续独立任务 |
| 权限提升 | Admin Token 进入 User 工具或 Delegation 用于确认 | audience/type/token-use 和路由双重隔离；确认只接受 User Access；危险 Admin 操作不在 registry | `AgentToolsAndPurchaseConfirmationIntegrationTest`、Agent registry/security tests | 配置错误仍可能扩大后端账户权限，需部署审计 |

## 安全扫描与处置规则

`script/verify-task19-security.ps1` 固定 Gitleaks 8.28.0、OSV-Scanner 2.2.2、Trivy 0.66.0、
Semgrep 1.136.0 和 ZAP 2.16.1 的镜像 digest。扫描器基础设施错误、缺失/空白/非法 JSON 报告或规则
FAIL 均记为失败，不能解释成“零发现”；ZAP baseline 的标准 exit 2 只表示已审计 WARN，exit 1/3 或报告
缺失仍失败。文件系统和最终镜像的未处理 High/Critical 为失败；不允许按整个 CVE 类别、目录或生产源码
做宽泛排除。

当前只有 `docs/security/task19-scan-waivers.json` 中两项精确 DS002 豁免，责任方分别为 HotShop security
engineering 与 CI engineering，到期日均为 2026-12-31：Agent 仅由 root 将只读 service key 复制为
UID 10001 的 mode 0400 tmpfs 文件，随后使用 `setpriv` 清组、置 `no-new-privs` 并降权；TASK-19 非生产
orchestrator 仅为本轮唯一 project 操作 Docker socket。两者都有源码、运行时容器测试、身份检查和限期
迁移措施；任何新增豁免仍须逐项记录组件、规则、可利用性证据、责任人、到期日和补偿控制。

ZAP 只对本轮唯一 Compose project 的 Web 入口做被动 baseline，不调用业务破坏 API。测试结束按
project label 与启动前记录的 image ID 清理；身份不匹配时拒绝删除并让 gate 失败。

## 明确不宣称的保证

本模型不证明没有未知漏洞，不把单元 Mock 当作网络故障，也不把静态审查当作动态攻击成功。TASK-19
只验证确定性功能、安全边界和恢复不变量；正式容量、生产 WAF、密钥托管、镜像签名和持续运行期间的
检测响应仍是部署层责任。
