# 独立复核修复与联合验证（2026-09-15）

## 范围与工作区保护

本次只处理 IR-01、IR-02、IR-03、IR-04，不表示完成全项目终验。

- 报告及经核实的开发基线：`a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`。
- 开始时原工作区位于 `master`，HEAD 与基线一致；只有原复核报告及附件目录未跟踪。
- 未发现仓库内或 `D:/`、`D:/Codex/`、`D:/Codex/Projects/` 的适用 `AGENTS.md`。
- 原工作区的用户材料保持原样。集成 worktree 复制了用户明确列出的报告及两个附件，其他附件未纳入提交。
- 集成分支：`review-fixes-integration`；工作区：`D:/Codex/Projects/hotShop-review-integration`。
- 功能分支及独立工作区：`review-fix-01-inventory-edit`（`hotShop-review-01`）、`review-fix-02-agent-recovery`（`hotShop-review-02`）、`review-fix-03-reconciliation`（`hotShop-review-03`）。四者从同一基线创建。
- 后增 `review-fix-joint-regression`（`hotShop-review-joint`），同样先从该基线创建以运行真实 HTTP/订单红测，再加入联合测试；测试提交以 cherry-pick 纳入集成分支。
- 不合并 `master`、不推送、不发布。未执行根目录 Maven clean；原 `target` 中的压测证据未改动。

## 验证环境

Windows PowerShell / Docker Desktop Linux 容器。通过项目 Maven Wrapper 核实 Maven 3.9.16，Temurin Java 21.0.11；Agent 测试镜像 Python 3.12.14；前端主机 Node 22.20.0、corepack pnpm@10.15.0（另核实 Node 容器 22.23.2）。主机 Maven 3.9.9 和默认 pnpm 11.19.0 不用于代替项目规定版本。日志中容器时间使用 UTC，前端本机时间使用 Asia/Shanghai（UTC+08:00）。

Java 容器默认 locale 为 en_US、编码 UTF-8。源文件遵守仓库 `.gitattributes`；PowerShell 脚本 CRLF，Java/XML/SQL/Markdown LF。数据库测试使用独立 Testcontainers 实例，不对原项目数据库执行诊断 SQL。Agent 使用可控 provider，不调用付费模型；pytest 显式加载 `pytest_asyncio.plugin`。

## 根因、设计与正式回归

| 问题 | 根因与修复 | 修复前真实失败 |
| --- | --- | --- |
| IR-01 | 旧资料表单带绝对库存；行锁不能识别旧值。普通 PUT 仅写资料，独立 stock-adjustments 接口使用 delta + expectedVersion + reason，行锁/条件更新及事务审计；所有在线库存扣减/回补同步维护 version 和独立预期余额。 | 真实 MyBatis/MySQL：旧表单扣减后期望 99、实际 100；回补后期望 100、实际 99。额外 MockMvc + 真实交易复现库存 99 被写回 100；前端请求断言捕获 stock:100。 |
| IR-02 | 共享 HALF_OPEN 布尔标志没有释放所有权，也没有区分熔断周期。每次尝试取得身份独立的 admission，generation 限制结果影响范围，finally 只释放自己的资格；显式关闭 provider，保留取消及原始异常。 | 正式取消/关闭回归 2 failed；附件诊断退出 0 仅表示旧 bug 复现。 |
| IR-03 | 活动装载时的商品快照只减秒杀订单，遗漏普通购买、回补及调整。商品库存和活动配额分别使用迁移/创建时的独立余额，所有合法 delta 在原事务同一 SQL 中计入；单条 SELECT 比较两边。Redis 投影仍以活动配额为边界，允许异步取消投影尚未送达。 | 真实 ProductMapper 扣减后实际写入 MYSQL_STOCK_CONSERVATION_VIOLATION / CRITICAL，证据为库存 99 对旧快照 100。 |
| IR-04 | 局部 batch 没有限制全历史 XRANGE/HGETALL 及守恒扫描。活动索引用 ZSET LIMIT 1 轮转；事件、守恒、PEL、MySQL 反查均分配固定页预算，持久化游标。Lua 原子读取守恒页；跨页库存 revision 变化会重启累计，完整一致周期才作结论。 | 37 条历史、B=3 时实际 HGETALL=41，超出正式回归预算；修复测试进一步核对 Redis SLOWLOG 中 Lua 内部 XRANGE 和总工作预算。 |

详细命令、失败断言及正式测试映射：

- [IR-01 前端证据](review-fix-01-web-evidence.md)。
- [IR-01 MySQL、HTTP 与整数边界证据](review-fixes-2026-09-15/ir01-evidence.md)。
- [库存调整与对账真实联合证据](review-joint-evidence.md)。
- [IR-02 生命周期证据](review-fix-02-evidence.md)。
- [IR-03/04 守恒与扫描证据](review-fix-03-evidence.md)。
- [尚无消费组的启动边界证据](review-fix-03-startup-evidence.md)。
- [IR-01 基线 MySQL 失败摘录](review-fixes-2026-09-15/ir01-before.txt)。
- [IR-03/04 基线失败摘录](review-fixes-2026-09-15/ir03-ir04-before.txt)。

守恒边界具体为：商品 `expected_stock = 迁移／创建时 stock + 此后合法商品库存 delta`，普通购买、秒杀建单记负数，两类取消回补记正数，后台调整记带审计的有符号 delta。活动 `expected_available_stock = 迁移／创建时 available_stock - 此后该活动建单数量 + 该活动取消回补数量`，普通订单和后台商品调整不改活动配额。二者在原业务事务同一 UPDATE 中同步维护，不回算快照之前的历史订单；同商品多个活动共用商品余额，各有独立活动余额，因此不同装载时点不会重复减库存。

Redis 以本次活动装载的 `initialAvailableStock` 为基准，减去尚未回补的有效预占量；与 MySQL 各核算自己的状态，不要求异步投影瞬间一致。全历史 Redis 累计只有跨页 fence 一致且扫描结束才比对；中途预占或回补推进 revision，旧累计作废重启。真正单边篡改的实际库存仍生成 CRITICAL 及真实证据，不自动改回数据库库存。

主 agent 审查了实际实现与 SQL/Lua 写路径，而非只采纳子任务总结。审查追加了 provider 清理异常不能替换取消、反向数据库查询先 LIMIT 后关联、第三条 processing 才匹配时不得过早报缺失、库存调整与对账的真实联合测试。发现的报告 CP936 编码已明确修正为 UTF-8，不作为显示问题忽略。

额外发现 Jackson 默认将 delta `1.5` 截断为 `1`，并接受字符串 `"5"`。正式 HTTP 回归先得到 7 tests / 2 failures，再仅为库存 delta 添加严格 JSON 整数反序列化，7 tests 通过；合法负数保持原值，不更改全局反序列化规则或 OpenAPI 类型。主 agent 另补真实 MySQL 审计存储故障：通过测试库触发器拒绝成功审计，核对库存、余额和版本回滚，失败审计独立保留，移除故障后同版本可重新成功。

联合测试还发现 JDBC 调整后 MyBatis 一级缓存返回旧对象，造成库存响应和成功审计的 after 值错误。主 agent 保留 110／105 的原断言，将库存调整的前后快照都改由原 JDBC 事务作 FOR UPDATE 当前读（`92f7c2d`），没有全局禁用缓存。随后暴露活动已装载但尚未创建消费组的 `XPENDING NOGROUP`；新增独立红绿测试，仅将该准确 Redis 错误视为没有 PEL，事件与守恒扫描继续，WRONGTYPE／连接故障仍传播（`40c93be`）。两项都来自真实联合运行，未调整测试顺序或提前创建消费组掩盖。

主 agent 另外实跑了旧索引升级的 PowerShell 入口。修复前隔离 Redis 的 160 个旧成员触发 `ERR value is not an integer or out of range ... @user_script:7`：裸逗号被 PowerShell 解释，未作为 redis-cli 的 KEYS/ARGV 分隔符。改为显式字符串 `','` 后新增并运行 `script/tests/test_reconciliation_index_upgrade.ps1`：首轮只运行一页，退出后读取持久化游标，再续跑至 COMPLETE；逐一核对 160 个旧成员均在新索引中，原 SET 未丢失。测试通过且仅清理该脚本创建的独立 Redis 容器（network none、/data tmpfs）。这是命令入口缺陷修复，不把它掩盖为环境设置问题。

## API、数据库及升级兼容性

- 新增 `POST /admin/api/v1/products/{productId}/stock-adjustments`，整数 delta、字符串 expectedVersion 和原因；陈旧版本返回 `409 STOCK_ADJUSTMENT_CONFLICT`。
- 商品资料 PUT 改用 `AdminProductEditRequest`，无 stock；旧请求即使仍带 stock 也不能写库存。旧后台调整库存的客户端必须改用新接口。
- `ProductResponse` 增加必有字符串 version，数据库 version 仍为原 INT。前端展示 delta 调整结果，冲突后必须刷新再确认，使用生成请求类型。
- 运行时导出的 public/admin baseline 和生成客户端已更新，审计 action 增加 `CATALOG_STOCK_ADJUSTED`。旧 baseline 兼容检查明确失败（库存字段移除、version 必有），不是“完全向后兼容”；新 baseline 与运行时的检查不取代该兼容性披露。
- 新增 Flyway V1_9（商品 expected_stock、活动 expected_available_stock 的创建/迁移基准）及 V1_10（反向审计 keyset 查询索引），未修改已发布 migration、未新增外键。合法写入同步调整余额，直接篡改 actual 不会自动同步 expected。
- 升级需暂停旧写进程，完成 migration 并统一启用维护余额/revision 的新代码，不能让新旧库存 SQL 或配额 Lua 混跑。迁移仅建立当时基准，不追溯既有库存差异。
- 旧 Redis registry 使用显式可续跑的 `script/upgrade-reconciliation-index.ps1` 建立新 ZSET。它不在日常对账调用中执行；SSCAN COUNT 是软提示，维护工具在命令之间检查时间预算，不能抢占单条命令。日常 runBatch 使用硬 LIMIT/COUNT；索引缺失时明确记录待升级异常，不假装无活动通过。

## 已知验证边界

本次不会把 MockMvc/服务事务链解释为真实浏览器或公网 HTTP 全链路。联合测试使用真实 MySQL、Redis、Flyway、MyBatis、事务代理、订单/审计/Outbox 业务及 Lua；Rabbit 传输替身只用于将已写入的超时 Outbox 交给真实投影消费逻辑。

持续写入可能使某活动的完整 Redis 守恒扫描保持 IN_PROGRESS；游标、fence、重启及完成次数可查，不能将其解释为守恒通过。其他活动和逐事件检查继续推进。此设计仍需足够稳定的扫描窗口；没有声称在持续无限写入下完成全历史快照。

活动装载入口原有的 `totalStock <= 当前商品库存` 校验仍保留；售出后若当前库存低于活动总配额，重新装载仍可能返回 ACTIVITY_INVALID。本次去除了旧商品快照参与同版本 Redis 装载校验的条件，但未扩展成活动重装语义改造。预期余额用于检测迁移基准之后实际库存单边变化，不是历史事件溯源；同时篡改 actual 与 expected 的特权数据库写入不在该守恒检查能力内。

未运行本次任务范围之外的完整故障矩阵、真实 Qdrant、完整 Compose 浏览器闭环、长时稳态压测、全项目终验、远端 CI、发布或部署。dev-data 重复装载已实测；没有运行完整 load-data 压测或 observability 验收脚本。

## 集成执行结果

所有最终代码验证基于 `9feda3de5a736a13c078a9c4df0eddd83b38aa19`，不是各分支通过结果的拼接。摘录：[integration-final-tests.txt](review-fixes-2026-09-15/integration-final-tests.txt)。完整日志保留在本 worktree 的 `target/review-fixes/final-*`。

| 实际执行 | 结果 |
| --- | --- |
| 全 Java reactor：Maven Wrapper `-B -ntp -fae verify`，未执行 clean | **313 tests，0 failures/errors/skipped；BUILD SUCCESS，退出0**。09:35，2026-09-15 15:44:34 UTC 完成；打包与 JaCoCo report 成功。 |
| Java 分模块 | common 12、domain 16、database 39、security 36、task 81、portal 79、admin 50；infrastructure 无测试类。 |
| 核心真实测试 | InventoryEdit 5；AdminInventoryApi 7；SeckillOrderReliability 23；SeckillPaymentExpiredDelivery 6；InventoryReconciliationJoint 3，全部通过。联合类在全 reactor 中耗时20.31秒；此前同 HEAD 定向3项亦通过。 |
| 实际 Redis 命令预算 | B=3：HGETALL=4，HMGET=3，XRANGE COUNT 合计6／2条命令，包含 Lua 内部命令。 |
| Agent：Python3.12，`pytest -o addopts= -p no:cacheprovider -p pytest_asyncio.plugin -m 'not qdrant' --tb=short -q` | **267 passed、6 skipped、8 deselected，退出0，29.43秒**。6项运行时/镜像权限测试因未提供镜像配置跳过，8项真实Qdrant排除，均不计为通过。 |
| Agent 质量 | Ruff lint通过；严格mypy：54文件通过；两个变更Python文件格式通过。全量format退出1，仅原基线既有的 `tests/test_registry.py`，原文件未修改，同镜像基线复验也失败。 |
| 前端：Node22.20.0、corepack pnpm@10.15.0 | **86 tests／19 files通过**；format:check、lint、typecheck、build均退出0。 |
| 真实索引升级 PowerShell 入口 | `script/tests/test_reconciliation_index_upgrade.ps1` 退出0：第一步只处理一页，恢复后完整覆盖160成员，逐个验证ZSET成员且原SET完整。 |
| 运行时 OpenAPI | `script/generate-openapi.ps1 -UseExistingPackages` 退出0，从最终verify生成的应用包导出；public/user/admin文档与baseline相同。 |
| 契约及生成客户端门禁 | `script/check_openapi_compatibility.py` 四域通过；pnpm `api:check`、`api:check:admin`、`api:check:agent` 全部退出0，使用项目固定OpenAPI Generator7.14.0。 |

Java 最终实际入口：

```powershell
docker run --rm --name hotshop-review-final-java --mount type=bind,source=D:/Codex/Projects/hotShop-review-integration,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -ntp -fae verify
```

最终通过前的失败及重跑原因均保留：第一次在 `d9494398` 因3处既有迁移数量／版本断言过期失败；精确更新为11条及1.10，并加强旧数据和索引核验。第二次在 `d534c86` 出现1次种子数据初始化 TCP/JDBC Connect timed out，20项对账通过、该项未进入业务断言；无先前pause残留证据，具体网络原因未确定，同代码重跑不再出现。第三次同代码对账21项通过，但暴露2处Portal迁移夹具过期以及联合测试的2个旧缓存响应断言失败。缓存修复后定向运行2项通过，完整业务测试继续发现 NOGROUP；为其另写红测再修复，最终上述313项全通过。详细摘要见 [前两轮](review-fixes-2026-09-15/integration-first-attempts.txt)、[联合失败](review-fixes-2026-09-15/integration-joint-before.txt) 及 [联合证据](review-joint-evidence.md)。

前端全量格式首次因 core.autocrlf=true 检出的92个CRLF文件失败；只在集成工作区将这些文件换行规范为LF后通过，没有修改全局Git配置、Prettier规则或逻辑，Git index无额外内容差异。旧红测补丁的CRLF预检问题通过该补丁专用的仓库换行属性解决。全部保留首轮与规范后的日志。

额外严格比对发现 Agent baseline 的框架 `ValidationError` 少两个可选字段 `ctx`／`input`，兼容性门禁因此仍通过。以原 `a3e7a6c` 源码只读挂载、同测试镜像和 fake/deterministic provider 导出的完整 OpenAPI 与最终运行时文档**完全相等**，证明这是基线已有差异；Agent除reliability.py及其新测试外没有源码／依赖变化。未顺手修改该无关baseline或生成客户端。复验输出在 `target/review-fixes/agent-original-runtime-schema.log`；本次更改的public/admin契约与运行时一致。

## 临时资源与最终工作区

- Java使用独立Testcontainers数据库、Redis和RabbitMQ；测试运行器、测试容器和Ryuk均已回收。索引升级CLI的独立Redis使用`--network none`、`/data` tmpfs并自动删除。没有启动共享Compose项目或触碰用户数据库。
- 两次运行时OpenAPI导出各遗留一个本次新建的MySQL匿名卷。主agent先记录该次唯一容器ID、创建时刻与Mounts，确认卷不在调用前列表且无任何容器引用后，仅删除 `c9bb928a9ff7d568e705ad0e5b4b2572c5361fba98d5360e87379b3b6c6d4c4e`、`92a5ab9d9362fba8311ebaac58610180995359d6651b1e738b003ac9d4274b76`。所有临时OpenAPI容器、网络及独有镜像标签已由脚本回收；最终卷列表与最后一次调用前一致。
- 最后检查无运行容器、无本任务OpenAPI网络。`docker ps -a`仍有两个6／7周前的已停止历史容器，未改动；共享依赖缓存、既有卷／镜像未清理。未执行全局prune。
- 保留功能worktree、集成worktree、node_modules、target日志与测试报告以供独立验收，不把它们误当需要删除的用户数据。
- 交付报告提交后，集成 `git status --short` 为空；`git diff --check a3e7a6c55040bbebc8760750af4b5cfcfd0406aa HEAD`通过，变更文本严格UTF-8检查通过。最终SHA与状态快照另写入本地附录 [final-delivery.json](../../target/review-fixes/final-delivery.json)。
- 原目录仍为`master`／`a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`，`git status --short`仅保留开始时的两项：`?? docs/quality/independent-review-2026-09-15.md`、`?? docs/quality/review-2026-09-15/`。三份明确列出的材料副本已核对SHA-256与原件相同。
- 不合并master、不推送、不发布；停止在集成worktree待验收。

## 提交定位

| 分支／集成补充 | 提交 |
| --- | --- |
| IR-01：review-fix-01-inventory-edit | `454546e447136d319361d6814d8736a2b60f573f` |
| IR-02：review-fix-02-agent-recovery | `43be49931207509b803370827c4682057a3ee235` |
| IR-03/04：review-fix-03-reconciliation | `9dce891fdda265792eb180133a4f3c7668b215b2`（原主体 `c124de1`，启动边界 `40c93be`） |
| 联合测试：review-fix-joint-regression | `d81f38527ca99143d1e6933cdfa1a08fc93c7ad8`，集成 cherry-pick 为 `2cde344` |
| 主 agent：前端与生成契约 | `60a1e1a`、`5d9dc3d`、`320fdb4` |
| 主 agent：索引升级真实 CLI 修复 | `a3a7c11` |
| 主 agent：真实审计失败回滚测试 | `f3fcc2e` |
| 主 agent：精确迁移断言与旧库余额验证 | `d534c86` |
| 主 agent：JDBC 当前读修复响应与审计 | `92f7c2d` |
| 主 agent：Portal 迁移夹具精确版本 | `1658d8b` |

最终代码／测试 HEAD 为 `9feda3de5a736a13c078a9c4df0eddd83b38aa19`；后续提交仅收录最终报告与证据。交付分支包含报告提交的完整 SHA 由交接摘要及 `git rev-parse review-fixes-integration` 给出，避免在 Git 提交内容中自引用尚未生成的哈希。

## 最小独立复验命令

以下命令在集成 worktree 执行；重型 Java 测试与 OpenAPI 导出串行运行。Docker 必须可用；使用现有本地测试镜像与缓存。命令不执行 clean、部署或用户数据库 SQL。

```powershell
Set-Location D:/Codex/Projects/hotShop-review-integration
git branch --show-current
git rev-parse HEAD
git diff --check a3e7a6c55040bbebc8760750af4b5cfcfd0406aa HEAD

docker run --rm --mount type=bind,source=D:/Codex/Projects/hotShop-review-integration,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -ntp '-Dtest=InventoryEditContainerTest,AdminInventoryApiTest,InventoryReconciliationJointContainerTest,SeckillOrderReliabilityContainerTest,SeckillPaymentExpiredDeliveryContainerTest,SchemaConstraintTest,V15ToV16MigrationTest,LegacyTakeoverTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify

docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-integration,target=/review,readonly -w /review/agent -e PYTHONPATH=/review/agent/src -e PYTHONDONTWRITEBYTECODE=1 --entrypoint python hotshop-agent:task20-test -m pytest -o addopts= -p no:cacheprovider -p pytest_asyncio.plugin tests/test_circuit_lifecycle.py tests/test_reliability.py tests/test_cancellation.py --tb=short -q

corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web test src/pages/admin-products-page.test.tsx
corepack pnpm@10.15.0 --dir web typecheck
& ./script/tests/test_reconciliation_index_upgrade.ps1

# 上面的 verify 已构建当前 HEAD 的应用包。
& ./script/generate-openapi.ps1 -UseExistingPackages
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-integration,target=/review,readonly -w /review --entrypoint python hotshop-agent:task20-test script/check_openapi_compatibility.py
$env:HOTSHOP_OPENAPI_GENERATOR_DOCKER='1'
corepack pnpm@10.15.0 --dir web api:check
corepack pnpm@10.15.0 --dir web api:check:admin
corepack pnpm@10.15.0 --dir web api:check:agent
git status --short
```

运行时导出脚本会创建隔离 MySQL/network 并回收容器/network；其现有清理流程可能遗留 MySQL 匿名卷。复验时记录该次容器 Mounts，仅回收经确认属于该次调用且无容器引用的卷，不能全局 prune。主 agent 本次的归属核验和清理结果见集成结果。
