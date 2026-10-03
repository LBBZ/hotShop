# 2026-10-03 后台排障功能审计与修正

[证据索引](evidence-index.md) · [结构化结果](admin-functional-audit-2026-10-03.json) · [后台架构](../architecture/admin-operations.md) · [复现入口](../../web/README.md#验证)

本轮转向近期界面、购物助手和登录恢复工作之外的后台排障流程，检查异常查询、活动加载和失败消息重放。
基线为 `3fd656254499ef217c615e980a1adeb7af21246e`；主要修复为
`91f9f3ea9a1a646d1cec2fa85d5c8bc71776299a`，补充写请求超时与结果语义的提交为
`50761b0b39478aa229bbccd601f8aadc9d3f44ff`。最终浏览器使用后者构建的 Web，执行时只有文档尚未提交。
日期采用 Asia/Shanghai。

## 发现与修正

| 触发条件 | 原有行为与影响 | 本轮修正 |
| --- | --- | --- |
| 异常、人工队列或支付超过 20 条 | API 返回下一页游标，但页面没有入口，后续记录不可达 | 三个列表分别提供首页、上一页、下一页，互不覆盖游标 |
| 支付查询或对账摘要失败 | 四个请求由一个 `Promise.all` 管理，一项失败隐藏全部已成功的数据 | 独立加载与错误边界；仅重试失败面板 |
| 查询已解决异常、两天前的超时到账 | 页面仅取默认未关闭异常及首次打开时的 24 小时支付窗口 | 接入服务端已有状态筛选；支付支持 24 小时 / 7 天 / 31 天；刷新全部更新时间窗口 |
| 装载返回 HTTP 200、`consistent=false` | 页面只显示绿色加载成功，丢弃库存核验信息 | 单独展示核验告警、两侧库存及预约计数，并提供排障入口 |
| 活动加载被后端拒绝 | 失败信息仍使用普通绿色通知 | 错误样式与 `alert`，保留 Problem code / request ID 及待操作对象 |
| 活动加载或重放尚未完成 | 可以关闭确认框、改变原因或选另一条记录；旧请求仍在执行 | 请求期间锁定当前资源与原因，阻止切换、重复提交和误导性的取消 |
| 写请求一直没有响应或成功响应丢失 | 可以无限等待；无结果也会被描述为未完成 / 失败 | 两项请求增加 15 秒网络截止时间；展示“结果尚未确认”，引导核实业务及审计记录，不自动重放 |
| 展示支付成功状态 | `SUCCEEDED` 没有被识别为成功；正向状态采用不精确子串匹配 | 仅确切的成功状态显示绿色，`LATE_SUCCEEDED` 仍提示关注 |

关键入口为 [异常页](../../web/src/pages/admin-exceptions-page.tsx)、[活动页](../../web/src/pages/admin-activities-page.tsx)、
[Outbox 页](../../web/src/pages/admin-outbox-page.tsx)及[管理 API 封装](../../web/src/features/admin/admin-api.ts)。
分页期间保持同一支付时间窗口；筛选变化只重建对应列表，刷新全部保留筛选并从首页重新查询。
后来页为空或失败时仍可返回上一页。移除了人工队列客户端中服务端并不支持的 `status` 参数。

## 验证与失败记录

新增 13 项行为回归，分三次先验证旧实现：异常页与重放有 6 项失败，活动反馈与等待状态有 3 项失败，写请求超时与不确定结果有 4 项失败。
修复后，相关 16 项测试全部通过，包含 3 项原有 Outbox 回归。前端完整测试为 **131 通过、0 跳过**，共 28 个测试文件。
测试覆盖独立分页、局部错误重试、时间窗口与游标绑定、筛选 / 刷新重置、错误页返回、核验差异、操作对象锁定及无响应时停止等待。

格式检查、ESLint、TypeScript 与 Vite 生产构建通过。测试夹具中错误使用的 `exact` 查询参数、缺少的 `traceId` 和拒绝原因类型由类型 / lint 检查发现并修正；没有关闭这些检查。
Windows 格式检查采用 `--end-of-line auto`，运行依赖遵循冻结锁文件。

真实验收访问独立项目 `hotshop-task21-accept1002` 的构建后 Nginx、Admin、MySQL 和 Redis。
宿主为 Windows x64 / Node 22.20.0，Playwright 1.57.0，Chromium 143.0.7499.4；分别使用 1440×1000 桌面视口与 Pixel 7 模拟参数。
每种设备执行以下 9 项检查，共 **18 项**：

1. 对账异常使用真实签名游标前进到包含第 21 条记录的页，再返回。
2. 人工队列独立前进并返回。
3. 支付记录独立前进并返回。
4. 已解决异常可通过筛选查询。
5. 两天前的 `LATE_SUCCEEDED` 支付可通过 7 天窗口查询。
6. 仅支付端点注入 503 时，其余列表仍可读；移除故障后按原筛选重试成功。
7. 临时草稿活动的真实加载返回成功但库存不一致时显示告警，重载不把 Redis 的 19 自动改回 MySQL 的 20。
8. 真实加载已返回上游 200，但浏览器响应被扣住时，截止时间后提示结果尚未确认、恢复操作入口，且只发出一次加载。
9. 页面没有未捕获错误或整页横向溢出；人工查看桌面、手机筛选和活动告警截图。

首轮在登录完成标题处等待 5 秒超时，未记录到登录 HTTP 状态，不能据此确定根因；单独复查登录返回 200。
补充 HTTP 断言与明确的 15 秒浏览器断言期限后，第二轮通过三项分页检查，但筛选器的精确 label 查询未匹配。
检查无障碍树确认控件名称正确，改为按 combobox 的名称定位。之后 16 项基本检查连续通过；最终增加两端写请求响应丢失检查。
每次执行均核验并清理自己的临时事实，失败日志未被绿测覆盖。

原始组件红绿报告位于 `.local/verification/admin-investigation-20261003/`；每次浏览器运行生成独立的
`.local/verification/admin-investigation-adm*/` 目录。公开 JSON 仅提取统计、场景、版本与清理结果；镜像摘要和最终时间以该 JSON 为准。

## 测试数据与复现

```powershell
corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web exec playwright install chromium
node script/verify-admin-investigation.mjs hotshop-task21-demo0001 http://127.0.0.1:18080
node script/check-docs.mjs
```

项目名与端口替换为已启动的独立演示，前置条件见 [Web README](../../web/README.md#验证)。
测试直接创建带随机标记的查询夹具，验证的是管理查询与交互，不能据此宣称这些订单经过了完整下单支付链路。
活动为草稿，差异只注入其专属 Redis 库存；测试在 `finally` 恢复库存、移除该活动的 Registry / index、等待当前发现缓存退场，并清理自己的行和 Key。
后端生成的追加式登录 / 加载审计保留，不删除审计记录。支付故障与响应丢失由浏览器路由注入，未关闭真实认证、CSRF 或限流。

## 源码审查与后续判断

[AdminOperationsService](../../admin/src/main/java/com/real/admin/service/AdminOperationsService.java)和
[查询仓库](../../domain/src/main/java/com/real/domain/adminops/AdminOperationsRepository.java)已有按筛选绑定的游标及有限分页；本轮补齐页面接入。
[Outbox 服务](../../admin/src/main/java/com/real/admin/service/AdminOutboxService.java)仍通过 MySQL 行锁将 `FAILED` 改为 `NEW`，其他状态拒绝，发布继续交给 Task。
本轮不修改此状态机。新的超时处理也不能撤销服务器事务；“没收到结果”必须与“服务器明确拒绝”区分。

活动重装仍是下一项需要先设计业务规则的工作：

- [装载器](../../domain/src/main/java/com/real/domain/service/seckill/FlashSaleActivityLoader.java)在写 Redis 前验证 `totalStock <= catalogStock`；已销售后的总配额与当前商品余量并不总能满足这个初次装载条件。
- 订单创建和超时回库会推进活动 `version`，而 [装载 Lua](../../domain/src/main/resources/redis/load-flash-sale-activity-v2.lua)在已有 Stream 事件时拒绝更新版本。这保住了预约事实，却也限制正常运营更新。
- 应先明确“配置版本、已承诺数量、可新增配额”各自的含义，再验证“售出一件 → 超时回库 → 重复装载”、同商品多个活动、普通购买及部分补偿等场景。直接放宽条件或用数据库当前库存覆盖 Redis，都不能证明库存守恒。

对账扫描的持续推进与 seen 空间也仍在[当前待办](../delivery/next-iteration.md)中：分页限制单次读取，并不保证持续写入下能完成一轮，也不保证总空间恒定。
本轮只审阅这些边界，没有容量或长期写入实验。后台列表的可变排序字段也不构成跨请求数据库快照；刷新用于重新读取当前范围，不承诺并发更新时的历史快照一致性。

本报告是上述功能的有界审计，不覆盖全仓所有功能、真实手机 / Safari、生产负载或真实支付渠道。
