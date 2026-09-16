# 审计写入与读取契约清点

范围：生产Java源码全部`INSERT INTO audit_log`及`AuditLogWriter`调用方。迁移中的历史回填不是运行时写入入口。
本轮没有改变已有记录名称、数据库CHECK或追加式触发器。

## 五个实际SQL写入入口

| 写入入口 | 上游动作 / 资源 | actor / source / result |
| --- | --- | --- |
| [JdbcAuditLogWriter.append/appendFailure](../../security/src/main/java/com/real/security/audit/JdbcAuditLogWriter.java) | 共享AuditEvent，细分见下表 | 共享枚举与脱敏器；失败独立事务 |
| [AgentToolAuditWriter](../../domain/src/main/java/com/real/domain/agenttools/AgentToolAuditWriter.java) | 用户工具、购买草稿、确认的成功/拒绝 | 工具AGENT委托USER；确认USER；AGENT_API；SUCCESS/FAILURE/DENIED |
| [AdminAgentToolAuditService](../../admin/src/main/java/com/real/admin/agenttools/AdminAgentToolAuditService.java) | AGENT_TOOL_INVOKED / AGENT_TOOL或AGENT_CONFIGURATION_DRAFT | ADMIN（不伪造用户委托）、AGENT_API；SUCCESS/DENIED/FAILURE |
| [SeckillProcessingService.finishCompensation](../../task/src/main/java/com/real/task/seckill/SeckillProcessingService.java) | RESERVATION_COMPENSATED / FLASH_SALE_RESERVATION | SYSTEM、TASK、SUCCESS；部分关联ID可空 |
| [OrderTimeoutService.insertInventoryCompensationAudit](../../task/src/main/java/com/real/task/timeoutOrderTask/OrderTimeoutService.java) | INVENTORY_COMPENSATED / SALES_ORDER或SALE_RESERVATION | SYSTEM、TASK、SUCCESS；request/trace可空 |

## 通用AuditEvent调用方

| 调用方 | 全部action | resource |
| --- | --- | --- |
| [SecurityAuditService](../../security/src/main/java/com/real/security/service/SecurityAuditService.java) | AUTHENTICATION_LOGIN、REFRESH_TOKEN_REUSE_DETECTED、AGENT_DELEGATION_ISSUED | AUTHENTICATION_SESSION、REFRESH_TOKEN_FAMILY、USER |
| [AdminProductAuditService](../../admin/src/main/java/com/real/admin/service/AdminProductAuditService.java) | CATALOG_PRODUCT_CREATED、CATALOG_PRODUCT_UPDATED、CATALOG_STOCK_ADJUSTED、CATALOG_PRODUCT_DELETED | CATALOG_PRODUCT |
| [AdminFlashSaleActivityLoadService](../../admin/src/main/java/com/real/admin/service/AdminFlashSaleActivityLoadService.java) | FLASH_SALE_ACTIVITY_LOADED | FLASH_SALE_ACTIVITY |
| [AdminOutboxService](../../admin/src/main/java/com/real/admin/service/AdminOutboxService.java) | OUTBOX_REPLAY | OUTBOX_EVENT |
| [PaymentCallbackAuditService](../../portal/src/main/java/com/real/portal/payment/PaymentCallbackAuditService.java) | MOCK_PAYMENT_CALLBACK_ACCEPTED、MOCK_PAYMENT_CALLBACK_REJECTED | PAYMENT_CALLBACK |

## 用户Agent与确认：不能遗漏拒绝路径

| 上游入口 | action | resource |
| --- | --- | --- |
| [AgentToolController](../../portal/src/main/java/com/real/portal/controller/AgentToolController.java)的search/get/compare products、list my orders/reservations | AGENT_TOOL_INVOKED | CATALOG_PRODUCT、SALES_ORDER、SALE_RESERVATION |
| [AgentToolService.createDraft](../../domain/src/main/java/com/real/domain/agenttools/AgentToolService.java)，Controller失败路径 | AGENT_TOOL_INVOKED | PURCHASE_DRAFT |
| [PurchaseConfirmationService.issue/consume/revoke](../../domain/src/main/java/com/real/domain/agenttools/PurchaseConfirmationService.java) | PURCHASE_CONFIRMATION_ISSUED、PURCHASE_CONFIRMATION_CONSUMED、PURCHASE_CONFIRMATION_REVOKED | 签发/撤销：PURCHASE_CONFIRMATION；消费：SALES_ORDER |
| [PurchaseConfirmationController](../../portal/src/main/java/com/real/portal/controller/PurchaseConfirmationController.java)的三个失败分支 | PURCHASE_CONFIRMATION_ISSUE_DENIED、PURCHASE_CONFIRMATION_CONSUME_DENIED、PURCHASE_CONFIRMATION_REVOKE_DENIED | PURCHASE_DRAFT；result=DENIED |

管理员三个工具的合法资源由[AdminAgentToolService](../../admin/src/main/java/com/real/admin/agenttools/AdminAgentToolService.java)
固定：statistics/anomalies为AGENT_TOOL，允许的配置草稿为AGENT_CONFIGURATION_DRAFT；拒绝也追加审计。
Agent路由在调用工具之前拒绝高风险请求时没有Java工具调用，不能把它声称为执行过后台高风险操作。

## 兼容与覆盖策略

写入Action共20个、ResourceType共15个；PAYMENT_ORDER保留已有声明，即使当前上述调用方没有直接使用。
本轮补8个action、5个resource，不仅修复浏览器碰巧遇到的记录。
action/resourceType在DB是开放VARCHAR：读取必须返回原字符串，未知值不能使整页或lookahead失败，前端按文本转义显示。
精确筛选支持未知值，不提供只列已知enum的下拉框来隐藏历史记录。

actor/delegatedActor/result/source受DB CHECK限制；当前合法值与共享枚举一致（source包括TASK和MOCK_PROVIDER）。
本轮保持这些字段的封闭契约；未来扩展须同时更新CHECK、枚举与OpenAPI，不允许单方写入新值。
非Agent后台与支付/安全写入仍经过共享AuditEvent。Task历史FLASH_SALE_RESERVATION与超时SALE_RESERVATION
都保留原名，不能修改历史数据来统一显示。

真实MySQL测试位于[AdminIdentitySecurityTest](../../admin/src/test/java/com/real/admin/AdminIdentitySecurityTest.java)：
`mixedAgentAuditRemainsReadableIncludingLookaheadBoundary`、
`everyAuditWriterCodeCanBeReadAndFilteredWithoutDroppingRecords`、
`administratorAllowedAndRejectedToolsRemainQueryableOnRealMysql`。
区分真实写入器/HTTP操作与枚举历史夹具；业务确认完整流程另由
[AgentToolsAndPurchaseConfirmationIntegrationTest](../../portal/src/test/java/com/real/portal/agenttools/AgentToolsAndPurchaseConfirmationIntegrationTest.java)覆盖。
实际运行结果以[本轮报告](task-21-reconcile-01.md)为准。
