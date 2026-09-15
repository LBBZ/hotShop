# TASK-21 最终文档、演示与简历交付

## 结论与范围

TASK-21交付记录；不是全项目终验声明。起始基线为 `10d82528aab71766ab5aa6020180ae4935f96359`。
主仓库开始时master、工作区干净；按用户授权创建 `task-21-delivery` 与
`D:/Codex/Projects/hotShop-task21`，所有改动/提交只在该worktree。
未发现仓库内或D:/、D:/Codex、D:/Codex/Projects适用AGENTS.md。
用户明确授权优先于路线文档中的默认不建分支/不提交规则。没有合并或推送master。

结论：**部分完成，审计浏览器闭环阻塞**。文档、架构/ADR、双版简历、面试材料与隔离演示入口已交付；
真实浏览器核心交易/库存/权限/英文RAG通过，但审计列表500，中文预设引用探针未满足。
业务缺陷依照用户范围要求只记录，见[阻塞报告](task-21-blockers.md)。不宣布全项目终验通过。

## 验收映射

| 要求 | 交付文件 | 验收依据 |
| --- | --- | --- |
| 项目定位、功能、技术栈、启动、账号、测试、性能和局限 | [README](../../README.md) | 核对POM、Compose、锁文件、安全/库存/Agent源码，替换旧说明。 |
| 系统、进程、模块、交易/消息、权限与Agent图 | [当前架构](../architecture/current-state.md)、[消息](../architecture/reliable-messaging.md)、[工具确认](../architecture/agent-tools-and-confirmation.md) | Mermaid实际解析及浏览器渲染，保持既有架构文档入口。 |
| 七项关键ADR | [ADR-002](../architecture/adr/ADR-002-modular-processes.md)至[ADR-008](../architecture/adr/ADR-008-fake-model-ci.md) | ADR-001保留；记录当前实现取舍与局限。 |
| 库存基线/版本/对账与至少一次边界 | [数据库](../architecture/database-schema.md)、[Stream](../architecture/stream-order-processing.md)、[后台](../architecture/admin-operations.md) | V1.9 expected基线、合法同步SQL、fence/seen有界扫描；不宣称无限写入一定完成。 |
| 测试/故障/Agent/权限/CI证据 | [证据索引](evidence-index.md) | 分SHA、分命令、区分实际/历史/未跑，不相加测试数。 |
| 可复现启动及5～8分钟演示 | [启动脚本](../../script/task21-demo.ps1)、[overlay](../../docker-compose.demo.yml)、[7分钟脚本](../delivery/demo.md) | 本机空卷隔离启动与直接Nginx浏览器验证，具体结果见下文。 |
| 两套简历及面试问答 | [职业材料](../delivery/career.md) | 每条主张对应源码/报告，禁用线上规模、收益、真实支付、微服务、稳定高QPS。 |
| 下一轮问题清单 | [优化清单](../delivery/next-iteration.md) | 按风险收益记录范围与验收，本轮不实施业务/性能扩展。 |
| 新机器可复现 | README与实际命令 | 本机全新Compose project验证，未在另一台新机器执行；严格的新机器验收项未满足。 |

## 环境与隔离

- 日期：2026-09-16（Asia/Shanghai）；容器UTC。
- Windows、Docker Desktop Linux Engine29.6.2、Compose5.3.1；Docker VM32 vCPU、8184864768 bytes内存。
- PowerShell7.6.5、Node22.20.0、pnpm10.15.0；项目Java21/Maven Wrapper3.9.16，Python镜像3.12.14。
- 应用镜像由本worktree源码构建；Java Dockerfile显式C1 (`-XX:TieredStopAtLevel=1`)。
- 复用了Docker镜像/BuildKit依赖缓存；未复用数据库、Redis或RabbitMQ业务数据，未使用其他项目密钥。
  本轮不测零缓存首次安装/拉取成本，也不把缓存构建耗时当新机器首次启动耗时。
- FakeModel+deterministic，真实Qdrant索引5点；未注入付费模型/embedding Key。
- 原有两只历史停止容器及既有卷/镜像保持，原性能target未读取修改或删除；没有全局prune。

## 实际执行与初次失败

PowerShell7，工作目录均为本任务根目录，除另行注明。完整本机日志在忽略的`target/task21/`。
只将脱敏摘要纳入版本化证据，不提交浏览器profile、Cookie、JWT、私钥或完整容器日志。

| 命令/阶段 | 当次结果 | 证据与边界 |
| --- | --- | --- |
| `git status --short` / `git rev-parse HEAD` / `git worktree add ... -b task-21-delivery <基线>` | 干净且基线完全匹配；worktree创建成功 | 不修改master。 |
| `verify-task19-e2e.ps1 -ProjectName hotshop-task21-e2e-0916-01` | preflight拒绝项目名，退出1，未创建资源 | 现有脚本要求hotshop-task19前缀；操作者命名错误，不计业务失败。 |
| `verify-task19-e2e.ps1 -ProjectName hotshop-task19-delivery091601 -EvidenceDirectory target/task21/e2e-0916-02` | 应用启动后Nginx shell readiness超时，退出1；浏览器未进入 | `e2e-run-02.log`，清理摘要containers/volumes/networks/images均0，clean=true。 |
| 新脚本Start，project `hotshop-task21-demo091601` | 容器及迁移成功，compose wait已退出migrator时返回no containers，脚本退出1 | 后续docker inspect退出码0；应用三个探针均200、匿名Agent401。项目保留后仅Stop。 |
| 修正新脚本迁移等待并Start，project `hotshop-task21-demo091602` | 空卷Flyway、所有进程、seed、知识索引和管理员活动装载成功 | `demo-start-02.log`；127.0.0.1:18080，后端loopback随机端口，首次seed活动1天。 |
| `corepack pnpm@10.15.0 --dir web install --frozen-lockfile` | 退出0 | 新worktree安装，17.6s；已有包缓存，不是零缓存下载。 |
| `corepack pnpm@10.15.0 --dir web test` | 86 tests /19 files通过，退出0 | `web-tests.log`；31.79s，单元测试，不是E2E。 |
| `corepack pnpm@10.15.0 --dir web typecheck` | 退出0 | `typecheck.log`。 |
| `pwsh -NoProfile -File script/tests/test_task21_demo.ps1` | 全部安全/迁移等待场景PASS | 已有资源拒绝、native查询失败关闭、环境恢复、容器退出7不能被docker退出0掩盖、missing/ambiguous拒绝。 |

TASK19探针诊断：在本轮Nginx镜像使用含CRLF的 `set -eu` 多行字符串执行 `sh -lc`，实际返回
`sh: set: line 0: illegal option -`；源码PowerShell here-string按仓库CRLF检出。
这是可复现的本地脚本问题证据，非完整19项套件失败根因全部排除；原Nginx镜像的HTTP功能探针另验正常。
本轮不改旧TASK19脚本，不将其失败掩盖为通过，后续入口见优化清单。

## 非文档变更披露

只增加本地演示与验证入口，没有修改Java/Python交易实现、迁移或授权：

- `docker-compose.demo.yml`：引入现有Web runtime，独立tag/项目、loopback端口覆盖。
- `script/task21-demo.ps1`：生成本项目随机env/keys，空项目首次seed，默认Fake/deterministic、显式Mock payment。
  复用现有task13测试seed，唯一数据调整为活动窗口30分钟改1天。Restart不重seed，Stop不删除数据。
- 最小启动修正：根据本机Compose5.3.1真实失败，使用 `compose ps -a -q` 找到唯一migrator，
  `docker wait`并验证其输出的容器退出码0；不跳过迁移。
- `web/playwright.delivery.config.ts`和[真实交付测试](../../web/e2e/task-21-delivery-real.spec.ts)：直接运行Nginx上的页面动作。
- [文档验证入口](../../script/verify-task21-docs.mjs)：检查本轮Markdown相对路径与Mermaid浏览器解析渲染。

## 复验命令

参照README开始一个新project，项目名必须全新；演示测试会写入该隔离数据。

```powershell
Set-Location D:/Codex/Projects/hotShop-task21
pwsh -NoProfile -File script/tests/test_task21_demo.ps1
pwsh -NoProfile -File script/task21-demo.ps1 -Action Start
# 上一条打印project名；默认前端18080。记录该名字用于Stop/Restart。
corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web exec playwright install chromium
corepack pnpm@10.15.0 --dir web exec playwright test --config playwright.delivery.config.ts
# 文档语法与实际SVG渲染，临时QA依赖不修改web依赖。
npm install --prefix target/task21/doc-tools --no-audit --no-fund mermaid@11.12.0 jsdom@26.1.0
node script/verify-task21-docs.mjs
git diff --check
```

## 未运行与局限

本轮未重新运行全Java reactor、全Agent评测/真实厂商模型、全Qdrant测试矩阵、全故障恢复矩阵、
安全扫描全套、长期压测或另一台新机器安装。基线CI的成功仅按公开job/step核验，详见证据索引。
TASK20性能目标仍未达；5000 requested RPS、尾部完成、dropped、登录与预约延迟口径、RabbitTTL保留均按原报告陈述。
库存基线/持续写入对账局限、Agent进程内运行恢复限制继续存在。

## 最终结果

### 真实浏览器结果

命令：`corepack pnpm@10.15.0 --dir web exec playwright test --config playwright.delivery.config.ts`。
源码业务部分未偏离基线；测试和启动脚本为本任务工作树新增，最终对应提交另在提交定位节登记。
运行于真实Chromium143.0.7499.4 / Playwright1.57.0，直连Nginx，不启动Vite，不mock接口。

| 场景 | 结果 | 验收结论 |
| --- | --- | --- |
| 注册/登录、刷新恢复、购买、Mock成功支付、他人订单拒绝、秒杀异步建单、Agent查询/草稿/用户确认 | 通过 | 用户核心链路完成真实浏览器验证。 |
| 元数据编辑保留另一用户扣减；delta旧版本409；刷新后成功调整 | 通过 | 库存编辑/并发演示完成。 |
| 管理Agent高风险请求拒绝，0 tool.started | 通过 | 至少一个明确权限拒绝例证；另有他人订单拒绝。 |
| 英文售后静态问题引用 | 通过 | RAG静态引用已真实浏览器验证，默认阈值保持0.15。 |
| 后台审计列表 | 失败，HTTP500 | 写入已发生，但合法Agent类型不在查询枚举，审计浏览器闭环未完成。 |
| 中文预设售后问题引用 | 未满足引用预期 | 返回“暂无可靠资料，我不知道该问题的答案”；同用户诊断候选score0.062578276低于0.15。有限确定性召回边界，不表示权限失守。 |

第三次拆分完整运行：**4 passed / 2 failed，49.0秒，退出1，无skip/retry**。
最初两次2项组合试跑分别暴露：未等待refresh恢复而再次导航、测试label定位不精确、审计500与RAG无引用。
已修测试同步与定位，后续拆分让其他场景独立报告，仍保留全部审计/中文引用失败断言；没有以过滤审计行、
清库、放宽阈值或业务修改制造绿色。中文第一次出现一次unavailable/泛化RetrievalError，之后为empty；
该瞬时异常具体根因未确定，不能与低分混为同一根因。英文用例是额外覆盖，不删除原中文失败用例。

只读DB补证：Flyway1.0～1.10共11条均success=1、外键0；当次商品913001 stock=expected_stock=44、
version=12；CATALOG_STOCK_ADJUSTED有2条SUCCESS和2条FAILURE。该单次余额观察不是完整守恒扫描或压测结果。

### 检查与证据边界

前端86项单元测试、类型检查通过；新Playwright配置起初未列入tsconfig导致ESLint报错，补入后类型与定向lint通过。
默认Playwright配置排除专用交付文件，mocked smoke `--list`仍为6项，防止默认命令意外连接真实演示。
此项仅核对测试选择，不声称重新运行mocked smoke。

文档检查使用Mermaid11.12.0和真实Chromium解析并渲染SVG；本轮修改的Markdown相对文件链接检查通过。
脚本不联网检查全部URL、不自动校验所有anchor；基线CI链接已通过公开API核验，新增本地锚点已按标题核对。
最终数目、敏感扫描及提交后复验另记下节。

**严格验收判定**：README/架构/ADR/证据/简历/面试/优化清单已完成；本机隔离启动已成功；
核心用户、模拟支付、秒杀、Agent确认、英文RAG和权限拒绝已实测；后台审计展示未完成；
原中文引用演示未满足预期，脚本明确展示拒答限制；另一台新机器验收未执行。因此TASK-21总体只能报告部分完成。
