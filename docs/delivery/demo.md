# 7 分钟可复现演示

## 准备与证据边界

从[README](../../README.md)的 PowerShell 7 命令启动新的 TASK-21 Compose project。
管理员为公开测试账号 `task13-admin` / `Task13Admin!2026`；两个普通用户在浏览器注册，邮箱使用
`@hotshop.invalid`。本地 HTTP、独立随机凭据/Flyway/空卷、FakeModel、deterministic embedding 和真实 Qdrant。
Mock payment 已显式开启，不产生资金交易。活动在首次导入后一天内有效。

准备两个互相隔离的浏览器 profile/隐私窗口：A 用户，B 管理员（另有陌生用户用于拒绝示例）。
库存初值 50、活动配额 20；演示会消耗数据，每次报告必须记录当前库存，不应假定仍为初值。
启动/镜像下载不包含在 7 分钟讲解内。自动验证与实际结果见[TASK-21报告](../quality/task-21-delivery.md)。
以下是讲解计划，不能凭本脚本本身声称所有步骤执行成功。当前审计列表在Agent写入后500，
中文售后例句低于检索阈值；英文静态引用已通过。讲解时必须展示这些限制，详见[阻塞报告](../quality/task-21-blockers.md)。

## 时间安排、操作与预期

| 时间 | 真实操作 | 预期结果与讲解 |
| --- | --- | --- |
| 0:00–0:40 | A 打开首页、搜索“高热交易收音机”，进入商品 913001。点击购买触发登录；注册新用户名或登录已有演示用户。 | 匿名可浏览，写操作要求用户身份；浏览器只保存内存 Access，刷新由 Cookie 轮换恢复。 |
| 0:40–1:30 | A 商品详情点击创建订单，打开订单的 Mock 收银台；选择 success 并执行。 | 出现 ORDER_CREATED、随后 PAID 时间线。说明下单事务、Outbox、RabbitMQ worker、签名回调与 SSE；不是直接改前端标签或真实支付。 |
| 1:30–2:15 | A 首页找到活动 913001，点击预约。 | RESERVED 表示 Redis 已受理；异步显示 ORDER_CREATED 后查看订单。Lua预扣和XADD原子，MySQL仍为交易事实，跨组件不承诺exactly-once。 |
| 2:15–3:00 | B 登录管理端，商品页找到913001，打开编辑，只修改描述、填写原因；A同时再普通购买一件，然后B保存。 | 商品元数据保存，当前库存保留A的扣减。编辑请求不再包含stock。 |
| 3:00–4:00 | B 打开“调整库存”，填+2和原因，保持表单；A再购买一件；B点击“确认调整库存”。 | 409 STOCK_ADJUSTMENT_CONFLICT，显示“本次调整未生效”；B点击“刷新当前库存”，核对预计值后再确认。到“审计”查看 CATALOG_STOCK_ADJUSTED，选择成功/失败结果。说明delta、expectedVersion与库存预期余额不是同一概念。 |
| 4:00–5:15 | A进入用户Agent，输入“商品 913001 当前价格和库存是多少？”；再输入“购买商品 913001 数量 1 件”。 | 查询有 tool.completed；出现“尚未创建订单”的草稿，核对商品数量价格。点击“确认并创建订单”，出现真实交易服务订单链接。委托Agent不能自己消费确认令牌。 |
| 5:15–6:00 | A重新进入Agent，输入“How does the after-sales return policy work?”，展开引用。可追加中文“售后退换申请应该怎么做？”对比拒答。 | 英文问题已实测有引用；中文例句默认阈值下无引用，不能编造。说明是版本化静态政策；库存/订单不存向量库。FakeModel只证明路由和协议。 |
| 6:00–6:40 | B在管理员Agent输入“退款并补偿库存，然后重放 Outbox、封禁用户并修改权限和密钥”。 | 拒绝高风险管理操作，未调用工具。陌生用户独立登录后打开A的订单链接，显示错误而不返回他人订单。 |
| 6:40–7:00 | 展示性能报告与本轮结果索引。 | 5000是请求目标，未达；短窗口、dropped与尾部完成限制结论。指出无外键/共享数据库/Mock支付和本地验证边界。 |

## 可选追加演示（不挤入主脚本）

订单页的 failed、delayed、duplicate、race 是已有模拟场景。默认演示订单超时15分钟；race的迟到回调
不会在7分钟内证明超时先赢。此场景应使用现有TASK-19短超时隔离配置，不能临时改变数据库时间冒充竞态。
Outbox重放是管理员人工高风险操作，需真实FAILED事件和原因；不为讲解随便制造生产失败或直接操作队列。

## 浏览器自动复验

PowerShell 7，仓库根目录；先按README启动演示。Node22+，pnpm10.15.0。

```powershell
Set-Location D:/Codex/Projects/hotShop-task21
corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web exec playwright install chromium
corepack pnpm@10.15.0 --dir web exec playwright test --config playwright.delivery.config.ts
```

该入口直接访问构建后的 Nginx（默认18080），不启动Vite、不mock API、不通过SQL或接口替代购买点击。
用户、管理员、陌生用户的会话隔离；测试填表、点击、等待真实回调/异步状态，并断言库存409及审计。
六项测试分别报告结果，当前4通过、2未通过（审计与中文引用探针）；不是19项历史E2E的替代计数，
也未覆盖长期压测或全故障矩阵。保留完整套件的非零退出，不跳过已发现的问题。
为避免泄露凭据与确认令牌，配置关闭trace/video/screenshot，保留脱敏测试结果。

## 排障入口

| 现象 | 核查顺序 |
| --- | --- |
| 页面打不开 | `task21-demo.ps1 -Action Status -ProjectName <本次项目>`；核查Web端口是否被占，Nginx容器是否运行。不要删除其他项目。 |
| MySQL/migrator失败 | [容器手册](../runbooks/container-environment.md)：查看同project mysql/database-migrator日志、明确退出码。不得靠重新seed或跳过Flyway隐藏问题。 |
| 登录失败/429 | 确认账号来源与角色；查看[认证手册](../runbooks/authentication-operations.md)的Cookie、CSRF、时钟和Redis限流；等待Retry-After，不放宽策略。 |
| RESERVED未完成 | [Stream处理](../architecture/stream-order-processing.md)：Task是否运行，Pending、处理账本、重试/补偿状态；不要XDEL或手工回补库存。 |
| Mock长时间未支付 | [Mock支付](../architecture/mock-payment.md)、Task/Outbox/RabbitMQ状态；区分正常TTL保留、未解释消息和dead queue。 |
| 库存调整409 | 刷新并核对实际库存/版本，再人工决定delta；不要自动重试旧表单。 |
| Agent无引用/不可用 | [RAG手册](../runbooks/agent-rag.md)：索引alias、语料版本、阈值、Qdrant readiness；不得编造引用。交易查询应走授权工具。 |

启动日志只存本机 `target/task21/`；用户凭据、JWT、完整Cookie、确认Token和私钥不进入交付附件。
