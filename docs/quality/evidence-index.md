# 测试与证据索引

核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`。本索引由当前源码、工作流及版本化报告交叉核对，不把历史测试数相加，也不把“脚本存在”记为“已运行”。TASK-21 当次新增运行与环境以[最终交付报告](task-21-delivery.md)为准。

## 1. 基线 CI：本轮已核验的公开记录

2026-09-16 通过 GitHub 公开 REST API 读取 [run 34998695750](https://github.com/LBBZ/hotShop/actions/runs/34998695750) 和 jobs，实际返回：

- `head_sha=10d82528aab71766ab5aa6020180ae4935f96359`，`event=push`，`status=completed`，`conclusion=success`。
- `run_started_at=2026-09-15T17:01:57Z`，`updated_at=2026-09-15T17:08:41Z`。
- 八个 job 全部 success。只按 job/step 返回值确认成功；本次未下载 JUnit artifact 重新统计数量。
- runner 配置为 Ubuntu 24.04；Java job 使用 Temurin 21，Web 使用 Node 22 / pnpm 10.15.0，Agent 使用仓库固定 Python 测试镜像与 fake/deterministic。

| 实际 job | 实际验证范围（核对当前 ci.yml 与 step 状态） | artifact / 明确排除 |
|---|---|---|
| Change detection | 分类变更路径 | 路径筛选用于选择其他 job，不是业务验证。 |
| CI and security policy | Python/PowerShell 策略回归、资源归属、actionlint、Gitleaks 仓库与引入历史、固定本地 Semgrep 规则 | `quick-security-34998695750-1`；不是独立安全审计或完整漏洞扫描套件。 |
| Java 21 verify | `./mvnw -B -ntp clean verify`，检查 Portal/Admin/Task JaCoCo 文件 | `java-reports-34998695750-1`；CI 在临时 runner 使用 clean，本地保留性能 target 时不应照搬 clean。 |
| Agent deterministic gate | Ruff、format、严格 mypy；非 Qdrant pytest；定向 coverage；quick eval | `agent-reports-34998695750-1`；`-m 'not qdrant'` 排除真实 Qdrant。运行时安全测试是否跳过以 JUnit 为准，本次未下载统计，不标全覆盖。 |
| Web quality and mocked smoke | format/lint/typecheck、test/coverage/build、生成客户端检查、`e2e/smoke.spec.ts` | `web-coverage-34998695750-1`；mocked Playwright 不是实际后端 E2E。“Upload failed Playwright evidence”步骤 skipped 因只在失败时上传，并非测试跳过。 |
| OpenAPI compatibility and drift | 运行时导出、兼容性比较、生成客户端漂移检查 | `openapi-reports-34998695750-1`；不是用户业务浏览器验证。 |
| Docker reproducible builds | Compose 解析、TASK-20 harness 静态检查（无负载）、Java/Agent/Web 镜像构建、运行用户检查 | 没有启动完整业务浏览器旅程或执行 target-5k。 |
| Required CI gate | 校验被选择的 job 结果 | 不能扩大成 Full verification gate 成功。 |

工作流：[ci.yml](../../.github/workflows/ci.yml)。artifact 名称由工作流 `run_id/run_attempt` 模板给出；报告默认保留 7 天，失败 Playwright 3 天，链接到 run 不保证未来 artifact 仍可下载。需要长期复核时应另存脱敏的机器结果并绑定 SHA。

可在 **PowerShell 7、任意工作目录、无需凭证**复核公开状态（GitHub API 受匿名限额约束）：

```powershell
$deliveryRun = Invoke-RestMethod 'https://api.github.com/repos/LBBZ/hotShop/actions/runs/34998695750'
$deliveryRun | Select-Object head_sha,status,conclusion,event,run_started_at,updated_at,html_url
$deliveryJobs = Invoke-RestMethod 'https://api.github.com/repos/LBBZ/hotShop/actions/runs/34998695750/jobs?per_page=100'
$deliveryJobs.jobs | Select-Object name,conclusion
```

另一份 [full-verification.yml](../../.github/workflows/full-verification.yml) 定义真实 Qdrant、full eval、Compose smoke、TASK-19 真实浏览器、故障矩阵与扫描。本索引没有核实其在本基线的成功运行，不能用上述 CI run 替代它。

## 2. 历史证据矩阵：逐版本陈述

下列“通过”是版本化报告记录的历史结果，本轮只核对报告与入口，不表示 TASK-21 重跑。`target/` 多为忽略的本机原始产物，源码克隆不含这些文件；缺少原件时按“报告摘录”使用，不当作已重新解析机器数据。

| 验证对象 | 提交 / 环境与入口 | 报告中的实际结果 | 排除与证据位置 |
|---|---|---|---|
| TASK-19 全栈浏览器、故障、安全 | 报告起始基线 `ed75306e82178b9e85075b72b9a13d81b4bd4b05f`，实际为 `task-19-reconcile-02` 工作树；报告版本 `3b8749481f19e7df9074c091616cccf37ca34484`；Docker Desktop、真实隔离 Compose、Chromium/Pixel 7。`verify-task19-e2e.ps1` / `verify-task19-faults.ps1` / `verify-task19-security.ps1` | real Playwright 19/19；故障矩阵 7/7；security 19 gates PASSED，ZAP 0 FAIL/7 WARN。Java 290；非 Qdrant Agent 250 passed/6 skipped/8 deselected；Qdrant 定向 36；quick 28/28、full 29/29；Web 83 | 这是原报告工作树的多入口结果，不是一个测试总数，也非当前基线重验。报告未给每个最终运行干净受测 SHA，勿把起始 SHA 写成最终代码 SHA。[TASK-19 报告](task-19-verification.md) 中列失败尝试、RunId 和 target 路径。 |
| TASK-20 正式性能探索 | `550231abfc9fde3228b307f88e1de99b5c35f3cb`，干净工作树；隔离 Compose，k6 内存上限 2 GiB；`verify-task20-performance.ps1 -Profile target-5k` | 5000 目标未达；正确性与完整性门禁通过，性能门禁 false；详见下一节 | RunId `run-reconcile02-default-0915-01`；[TASK-20](task-20-performance.md)，本轮未重跑压测。 |
| 独立复核诊断 | `a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`；只读当前 Python 源码、已有测试镜像、network none；隔离 MySQL 8.0.46 最小 SQL | Agent 250 passed/6 skipped/8 deselected；确认库存覆盖、半开取消泄漏和对账误报/无界扫描问题 | 诊断 SQL 不是应用 E2E；问题已由后续修复处理，不作为当前未修缺陷。[首轮报告](independent-review-2026-09-15.md)。 |
| 修复后统一集成 | `9feda3de5a736a13c078a9c4df0eddd83b38aa19`；Temurin 21、真实 MySQL/Redis/RabbitMQ Testcontainers；Python 3.12；Node 22.20.0 / pnpm 10.15.0 | 同一代码 SHA 全 Java 313，0 failure/error/skip；Agent 267 passed/6 skipped/8 deselected；Web 86/19 files；OpenAPI、索引升级 CLI 通过 | 6 个镜像/运行时测试缺配置跳过、8 个 Qdrant 排除。Agent 原基线 format 问题当时未修，后来 CI repair 修复。联合测试 Rabbit 传输替身不是 broker 故障矩阵。[统一集成报告](independent-review-fixes-2026-09-15.md)、[日志摘录](review-fixes-2026-09-15/integration-final-tests.txt)。 |
| 分页预占号去重补修 | `12015becabecf2a2bf2fb010a7ff3f8c68df3ed9`；Temurin 21、Maven 3.9.16、真实 Testcontainers；报告末尾精确 Maven 定向命令 | 40 tests，0 failure/error/skip；B=3 的 Redis 命令预算核验 | 仅可靠性26、库存编辑5、秒杀回补6、联合3；不是重跑313。未重跑 Agent/Web/Qdrant/浏览器。[补修设计](review-fixes-2026-09-15/dedup-design.md)、[集成摘录](review-fixes-2026-09-15/dedup-integration.txt)。 |
| CI repair 本地子集 | 起始 `904dda06a3412c240f60e91da7008e9bea3efd54`；完成后的基线 CI 见上节；源码最终 Rabbit 连接清理变动另复验 | Gitleaks 窄范围排除+合成负例、Ruff/mypy、Agent registry28、PowerShell策略通过；消息23通过后改连接清理，受影响1项再通过 | 23 和1不可相加宣称24项全量；本地报告未逐命令绑定最终SHA。[CI repair](ci-repair-2026-09-16.md)。 |
| Agent TASK-17 评测 | 报告版本 `8c1e4b16e73894a60527904f86c307269519e4ce`，报告未给每项受测代码 SHA；固定 Agent 测试镜像，fake/deterministic | RECONCILE-02 quick28/28，数据集校验15；非 Qdrant243 passed/6 skipped/8 deselected | 无 DeepSeek/Qwen/Bailian 在线调用，不将报告提交视作测试代码 SHA。[TASK-17](task-17-verification.md)；当前基线 quick 的 job 已通过但本轮未下载 JSON 计数。 |

## 3. 性能报告的必要口径

正式数据出自[task-20-performance.md](task-20-performance.md)，机器产物原位置为 `target/task20-performance/run-reconcile02-default-0915-01/summary.json`，不是本次新生成：

| 指标 | 正式结果及解释 |
|---|---|
| 施加目标 | 5000 requested RPS、1000 VU、10s 到达窗口、2s warmup、50001 唯一用户/库存。 |
| 完成与丢弃 | attempted/accepted=2206/2206；dropped iterations=47791；无 interrupted iteration。施加目标不等于实际完成量。 |
| 速率分母 | 220.6 = 2206 / 10s，完成量含 graceful-stop 尾部；原始 k6 执行15.8233s。不能称稳定吞吐或长时容量。 |
| 登录口径 | 每次迭代先真实登录，认证耗时占用 VU；预约 HTTP P95/P99=4547.55/5088.75ms，不含登录。不是提前持有 JWT 的纯预约基准。 |
| 建单延迟 | 异步订单 P95/P99=49785.16/52094.12ms，明显未达目标。 |
| 正确性 | 有效订单2206，MySQL/Redis库存47795；超卖、重复有效订单、差异、failed/stuck及未解释消息残留为0。只适用于该短时运行。 |
| Rabbit 保留 | raw ready=4412，其中 ORDER_CREATED 集成队列2206（无消费者）、15分钟TTL延迟队列2206；均逐订单核对 PUBLISHED Outbox。它们不计异常积压，也不意味着全部队列清空。 |
| 门禁 / 清理 | `businessCorrectnessPassed=true`、`runIntegrityPassed=true`、`target5000RpsActuallyReached=false`；所属容器/网络/卷归零。 |

RECONCILE-01 的短窗口探索和更早已作废 RunId 不用于证明上述默认配置。采样峰值是少量容器采样而非全生命周期精确高水位。吞吐、延迟、队列排除、资源峰值不能脱离版本化报告单独摘成简历成绩。

## 4. 当前源码的测试与故障定位入口

| 对象 | 测试 / 命令或工作流入口 | 能验证的边界 |
|---|---|---|
| 库存旧表单、显式调整与审计 | [InventoryEditContainerTest](../../task/src/test/java/com/real/task/seckill/InventoryEditContainerTest.java)、[AdminInventoryApiTest](../../admin/src/test/java/com/real/admin/AdminInventoryApiTest.java)、[联合测试](../../admin/src/test/java/com/real/admin/InventoryReconciliationJointContainerTest.java) | 真实数据库事务、普通/秒杀/超时变化、版本冲突和审计回滚；UI 人工可理解性需实际浏览器。 |
| Stream 提交边界、认领、补偿和对账 | [SeckillOrderReliabilityContainerTest](../../task/src/test/java/com/real/task/seckill/SeckillOrderReliabilityContainerTest.java) | 故障点与重复投递下事实恢复；有界扫描/预占去重，不证明无限写入必完成。 |
| RabbitMQ / Outbox / Inbox | [ReliableMessagingContainerTest](../../task/src/test/java/com/real/task/outbox/ReliableMessagingContainerTest.java)、[fault runbook](../runbooks/fault-injection.md) | confirm窗口、broker失败、重投递与死信边界；至少一次+业务幂等。 |
| 模拟支付竞争 | [PaymentTerminalRaceContainerTest](../../portal/src/test/java/com/real/portal/PaymentTerminalRaceContainerTest.java)、[SeckillPaymentExpiredDeliveryContainerTest](../../task/src/test/java/com/real/task/payment/SeckillPaymentExpiredDeliveryContainerTest.java) | 支付/取消终态及回补；不验证真实资金。 |
| 身份与委托 | [IdentitySecurityTest](../../portal/src/test/java/com/real/portal/IdentitySecurityTest.java)、[AdminIdentitySecurityTest](../../admin/src/test/java/com/real/admin/AdminIdentitySecurityTest.java)、[Agent确认集成测试](../../portal/src/test/java/com/real/portal/agenttools/AgentToolsAndPurchaseConfirmationIntegrationTest.java)、[Agent registry测试](../../agent/tests/test_registry.py) | issuer/audience/scope、owner、确认重放、高风险工具缺席；不是通用渗透结论。 |
| Agent 取消与恢复 | [test_circuit_lifecycle.py](../../agent/tests/test_circuit_lifecycle.py)、[test_reliability.py](../../agent/tests/test_reliability.py)、[test_cancellation.py](../../agent/tests/test_cancellation.py) | 半开探测租约、取消和资源释放，Fake/替身场景不证明远端模型可用性。 |
| RAG/模型契约/评测 | [eval_runner.py](../../agent/src/hotshop_agent/eval_runner.py)、[task17-v2.jsonl](../../agent/evals/task17-v2.jsonl)、[Provider契约](../../agent/tests/test_model_provider_contracts.py)、[RAG runbook](../runbooks/agent-rag.md) | quick为内存向量库，full为真实Qdrant；有限用例中的路由、引用、拒绝、安全、授权与生命周期。 |
| 真实浏览器与故障矩阵 | [verify-task19-e2e.ps1](../../script/verify-task19-e2e.ps1)、[verify-task19-faults.ps1](../../script/verify-task19-faults.ps1)、[full workflow](../../.github/workflows/full-verification.yml) | 真实隔离后端；本基线的当次执行状态见 TASK-21 报告，历史成功不自动继承。 |
| 契约与前端 | [ci.yml](../../.github/workflows/ci.yml)、[generate-openapi.ps1](../../script/generate-openapi.ps1)、[web/package.json](../../web/package.json) | 类型、兼容性、客户端漂移与单元/Mock测试；不可替代真实浏览器。 |

## 5. 独立复验命令与结果登记

**PowerShell 7，仓库根目录**（以下路径是本次独立 worktree）。依赖 Docker Desktop Linux containers；本机 Java 命令另需 JDK21，Web 需 Node22/Corepack/pnpm10.15.0。首次下载依赖与构建镜像时间不算日常运行耗时。命令是复验入口，除上节 CI 核验外，本文件不声称已执行。

```powershell
Set-Location D:/Codex/Projects/hotShop-task21
git rev-parse HEAD
git diff --check
# JDK21 + Docker，保留已有 target 性能证据，不执行 clean。
./mvnw.cmd -B -ntp -fae verify
# Node22；在 web 下运行 frozen install、测试和类型检查。
corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web test
corepack pnpm@10.15.0 --dir web typecheck
# 从当前源码构建独立 FakeModel 测试镜像，无需已存在的本机镜像。
docker build --target test -t hotshop-agent:task21-evidence -f agent/Dockerfile agent
docker run --rm --network none -e AGENT_MODEL_PROVIDER=fake -e AGENT_EMBEDDING_PROVIDER=deterministic --entrypoint python hotshop-agent:task21-evidence -m pytest -m 'not qdrant' -p pytest_asyncio.plugin -p no:cacheprovider
New-Item -ItemType Directory -Force target/task21-evidence | Out-Null
docker run --rm --network none -e AGENT_MODEL_PROVIDER=fake -e AGENT_EMBEDDING_PROVIDER=deterministic --mount "type=bind,source=${PWD}/target/task21-evidence,target=/reports" --entrypoint python hotshop-agent:task21-evidence -m hotshop_agent.eval_runner --suite quick --output /reports/quick-eval.json
```

需要完整旅程时，在同一根目录串行运行以下入口（会构建/启动各自隔离资源，可能较久；不与其他重型演练同时跑）：

```powershell
pwsh -NoProfile -File script/verify-task19-e2e.ps1 -TimeoutSeconds 1200
pwsh -NoProfile -File script/verify-task19-faults.ps1 -TimeoutSeconds 1200
# 性能为可选独立复验，会产生新 RunId，不能覆盖历史报告。
pwsh -NoProfile -File script/verify-task20-performance.ps1 -Profile target-5k
```

新运行需登记：HEAD、dirty状态、时间、操作系统/Docker版本、project和数据初始化、精确参数、退出码、机器报告、跳过项、失败尝试与仅归属于本轮的资源回收结果。未下载的 CI artifact、未运行的 full workflow、真实 Provider、长时稳态和另一台新机器均保持未核验，不由文档推断为通过。
