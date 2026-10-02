# 实现与验证证据索引

[文档中心](../README.md) · [当前 CI 定义](ci.md) · [后续工作](../delivery/next-iteration.md)

本索引将当前实现入口与历史验证分开。源文件说明能力如何实现；测试文件说明可执行的断言；报告只能证明其中明确记录的提交、环境和命令。本页不以旧的测试计数或绿色 CI 推断最新提交的运行结果。

## 当前实现入口

| 范围             | 设计 / 使用说明                                                                                        | 源码与检查入口                                                                                                               |
| ---------------- | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| 购物体验         | [Web README](../../web/README.md)、[交易旅程](../architecture/user-transaction-journey.md)             | [页面](../../web/src/pages)、[storefront 浏览器测试](../../web/e2e/storefront.spec.ts)、[smoke](../../web/e2e/smoke.spec.ts) |
| 交易与一致性     | [预约](../architecture/flash-sale-reservation.md)、[可靠消息](../architecture/reliable-messaging.md)   | [领域服务](../../domain/src/main/java/com/real/domain)、[异步任务](../../task/src/main/java/com/real/task)、Maven `verify`   |
| 认证与数据       | [认证手册](../runbooks/authentication-operations.md)、[数据库结构](../architecture/database-schema.md) | [Security 模块](../../security)、[Flyway 迁移](../../database/src/main/resources)                                            |
| Agent 与知识检索 | [工具与确认](../architecture/agent-tools-and-confirmation.md)、[RAG](../architecture/agent-rag.md)     | [Agent 测试](../../agent/tests)、[eval](../../agent/src/hotshop_agent/eval_runner.py)                                        |
| API 兼容         | [接口契约](../api/api-contract.md)                                                                     | [OpenAPI 基线](../api/openapi-baseline)、[CI](../../.github/workflows/ci.yml)                                                |
| 文档与自动化     | [CI 说明](ci.md)、[贡献指南](../../CONTRIBUTING.md)                                                    | [文档检查器](../../script/check-docs.mjs)、[CI 策略测试](../../script/ci/tests)                                              |

## 版本化报告

最新产品更新：[2026-10-02 商品与助手体验验收](shopping-experience-2026-10-02.md)，记录多商品目录、图片与规格持久化、推荐对比及确认购买的验证范围。

最新维护：[2026-10-02 验证环境与演示入口整理](maintenance-alignment-2026-10-02.md)，记录 Qdrant 版本对齐与真实检索回归。同日的[桌面与手机产品验收](product-acceptance-2026-10-02.md)记录购物流程与界面修正。此前的[文档与遗留内容整理](maintenance-2026-10-01.md)保留各自的检查范围和工具链限制。

| 报告                                                                                             | 适用范围                                                              |
| ------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| [2026-10-02 验证环境整理](maintenance-alignment-2026-10-02.md)                                   | Qdrant 1.19.1 真实回归、CI 版本一致性检查与演示入口更新               |
| [2026-10-02 产品验收](product-acceptance-2026-10-02.md)                                          | 独立容器中的 14 项桌面/手机流程、真实限流恢复、购物助手与仓库展示调整 |
| [2026-10-01 Spring Boot 4 升级](boot4-upgrade-2026-10-01.md)                                     | Java 依赖迁移、兼容修复与该轮 437 项 Java 测试；保留中途 JVM 崩溃记录 |
| [2026-09-30 技术栈现代化](modernization-2026-09-30.md)                                           | 工具链、镜像和启动脚本整理；具体执行边界见报告                        |
| [2026-09-16 验证矩阵](evidence-index-2026-09-16.md)                                              | 原 TASK-21 交付的源码 SHA、托管 CI 与各任务证据；其中分支名是历史标签 |
| [2026-09-16 CI 修复](ci-repair-2026-09-16.md)                                                    | 当时托管工作流问题与修复                                              |
| [独立审查](independent-review-2026-09-15.md)、[修复总表](independent-review-fixes-2026-09-15.md) | 当时发现的问题、红绿回归与剩余限制                                    |
| [TASK-19](task-19-verification.md)                                                               | 真实服务 E2E、故障与安全扫描的当轮范围                                |
| [TASK-20 性能](task-20-performance.md)                                                           | 固定受测提交和负载参数；正确性通过，5000 requested RPS 性能目标未达   |
| [TASK-21 交付](task-21-delivery.md)、[补充验收](task-21-reconcile-01.md)                         | 隔离演示、浏览器与检索验证及未解决事项                                |

更早的 `task-*`、`review-*`、`baseline` 报告和其文本/JSON 证据继续保留在本目录。旧工作树路径、临时 `target/` 输出和测试账号描述当时环境；复现实验时使用当前运行手册与独立环境。

## 如何新增证据

记录受测提交 SHA、是否含未提交变更、依赖/镜像、命令、时间、结果与排除项。失败后重跑时保留第一次失败的原因，不能只留下绿测。CI 状态链接应指向具体运行，不能用首页 badge 替代验证记录。

历史分支可在完全合并后清理，证据以提交 SHA 为稳定标识。不要因为文档整理重写原始日志、抹去失败记录或扩大性能、模型效果与安全结论。
