# HotShop 文档中心

[项目首页](../README.md) · [English overview](../README.en.md) · [参与开发](../CONTRIBUTING.md)

这里按使用目的组织文档。当前指南描述仓库现有实现；历史报告保留各自版本的测试、问题和结论。技术版本以依赖清单、锁文件、迁移文件和容器配置为准。

## 运行与使用

| 我想做什么                    | 从这里开始                                    |
| ----------------------------- | --------------------------------------------- |
| 启动完整商城并体验购买        | [演示指南](delivery/demo.md)                  |
| 核对售后、登录与支持能力      | [使用说明 / Help](product/help.md)            |
| 配置服务、端口、密钥和 Docker | [容器环境](runbooks/container-environment.md) |
| 修改页面、路由或交互          | [Web 开发](../web/README.md)                  |
| 运行 Agent、接入模型          | [Agent 服务](runbooks/agent-service.md)       |
| 更新知识库、切换向量方案      | [RAG 运维](runbooks/agent-rag.md)             |
| 理解尚未完成的工作            | [后续工作](delivery/next-iteration.md)        |

## 架构与契约

- [当前架构](architecture/current-state.md)：进程、模块、基础设施与信任边界。
- [用户交易旅程](architecture/user-transaction-journey.md)：页面与业务状态如何对应。
- [数据库结构](architecture/database-schema.md)：Flyway 迁移、库存事实、订单与认证数据。
- [限时预约](architecture/flash-sale-reservation.md)、[Stream 转单](architecture/stream-order-processing.md)、[可靠消息](architecture/reliable-messaging.md)、[模拟支付](architecture/mock-payment.md)。
- [后台运营](architecture/admin-operations.md)、[可观测性](architecture/observability.md)。
- [Agent 进程](architecture/agent-process.md)、[工具与购买确认](architecture/agent-tools-and-confirmation.md)、[RAG 设计](architecture/agent-rag.md)。
- [API 契约](api/api-contract.md)与 [OpenAPI 基线](api/openapi-baseline)：接口、身份、幂等与错误语义。
- [领域术语](../CONTEXT.md)与 [架构决策记录](architecture/adr)：统一概念与设计理由。

## 运行维护与验证

| 内容                 | 指南                                              |
| -------------------- | ------------------------------------------------- |
| 身份与会话           | [认证运维](runbooks/authentication-operations.md) |
| 后台审计与人工操作   | [审计运维](runbooks/audit-operations.md)          |
| 指标、日志与追踪     | [观测手册](runbooks/observability.md)             |
| 故障恢复验证         | [故障注入](runbooks/fault-injection.md)           |
| 自动化质量检查       | [CI 工作流](quality/ci.md)                        |
| 压力测试的运行与解释 | [k6 使用说明](../load/k6/README.md)               |
| 能力与验证证据       | [证据索引](quality/evidence-index.md)             |
| 安全设计             | [威胁模型](security/threat-model.md)              |

## 历史资料

`quality/` 中带任务编号或日期的报告是版本快照，不是当前安装说明，也不能代表最新提交已经通过同样验证。原始日志和失败证据保留在原路径，便于复核已有引用。

- [技术栈现代化记录](quality/modernization-2026-09-30.md)、[Spring Boot 4 升级记录](quality/boot4-upgrade-2026-10-01.md)。
- [原始基线](quality/baseline.md)、[构建基线](quality/build-baseline.md)、[TASK-21 交付](quality/task-21-delivery.md)。
- [历史任务规划](roadmap/TASK_CATALOG.md)、[历史总计划](roadmap/MASTER_PLAN.md)、[历史并行工作约定](roadmap/PARALLEL_EXECUTION.md)。这些是已完成迭代的背景材料。
- [项目讲解材料](delivery/career.md)：按证据描述技术取舍，个人贡献需自行核实。

更新入口文档时同步维护中文和英文 README，新增本地链接后运行 `node script/check-docs.mjs`。新增结论应注明受测提交、命令、环境和验证边界。
