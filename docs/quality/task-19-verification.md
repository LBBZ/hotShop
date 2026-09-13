# TASK-19 verification report

基线为 `ed75306e82178b9e85075b72b9a13d81b4bd4b05f`。本任务只补充端到端、故障与安全验证，没有开始
TASK-20 压测或 TASK-21 README/简历重写。运行时大报告位于忽略的 `target/`，本文只记录可复现入口、
证据分级和最终计数。

## 覆盖设计

新 real spec `web/e2e/task-19-agent-security-real.spec.ts` 显式 serial，Desktop Chromium 与 Pixel 7
各运行五个真实场景：User RAG 与实时工具、购买草稿/主动确认/双击/重放/篡改/跨 User、双浏览器
Session/Run 归属、Administrator 低风险读取与高风险拒绝、Agent/Qdrant 故障降级。它不使用
`page.route`、`route.fulfill`、伪 HMAC、provider callback、固定 sleep 或内存后端。

原有 `user-transaction-real.spec.ts` 继续负责匿名浏览、键盘登录、刷新恢复、普通支付四态、支付终态竞争、
秒杀预约/异步订单、交易 timeline、Last-Event-ID、Portal restart、redis-seckill 断连和所有权；原有
`admin-operations-real.spec.ts` 继续负责管理员异常定位、Trace 跳转、带原因的允许操作和审计事实。
TASK-19 不复制这些场景。

## 自动化入口

| 入口 | 内容 | 资源边界 |
|---|---|---|
| `script/verify-task19-e2e.ps1` | 唯一 Compose stack、原有两组 real spec、新 Agent/security spec；可选被动 ZAP | 启动前快照与创建后 ID 的差集登记；project label 只用于碰撞检测，不授予所有权 |
| `script/verify-task19-faults.ps1` | 上述真实浏览器故障 + 既有 Java Testcontainers commit-boundary suites | 继续执行各 gate 并写 `fault-matrix.json` |
| `script/verify-task19-security.ps1` | Gitleaks、OSV、Trivy fs/images、Semgrep、Java/Agent/Web 回归、ZAP | 固定版本/digest；报告缺失或 scanner 失败即失败 |

## 本工作树执行状态

下表是 2026-09-13 至 2026-09-14 对 `task-19-reconcile-02` 工作树的实际结果。失败尝试没有覆盖：
E2E 的 Node/Vite 原生 exit 139、故障矩阵首次 Maven launcher `stack smashing detected` exit 139、
不含 Git 的 Python 镜像造成的三项 CI test 环境错误，以及 security 调和期间的 scanner 下载/超时、
测试夹具 Gitleaks 命中、Agent 进程 exit 139/人工终止 exit 137、MySQL/Redis 启动竞态均保留为失败事实；
表中“通过”只对应随后取得明确 exit 0 且机器报告合法的运行。

| 验证 | 状态 | 结果/证据 |
|---|---|---|
| 初始 Git 基线 | 已自动验证 | HEAD `ed75306e82178b9e85075b72b9a13d81b4bd4b05f`；按要求保留既有未提交工作树 |
| 资源所有权回归 | 已自动验证 | 13 个场景通过；sentinel container/volume/network/image ID 不变；缺失、空、非空环境值以及成功、startup、Playwright、query、interrupt、OpenAPI build/export/JSON 路径的 cwd/env 恢复与精确清理通过；`target/task19-ownership-regression/0244727a67ae/summary.json` |
| CI policy tests | 已自动验证 | 45/45，`check_ci_policy.py` 通过 |
| Compose config | 已自动验证 | `docker compose --env-file .env.example config --quiet`，exit 0 |
| Java 21 Maven Wrapper `clean verify` | 已自动验证 | 290 tests，0 failure/error/skip，BUILD SUCCESS；`target/task19-reconcile/java/maven-clean-verify.final.log` |
| Agent | 已自动验证 | mypy 53 files；非 Qdrant 250 passed/6 skipped/8 deselected；真实 Qdrant 36 passed；quick 28/28；full 29/29 |
| Web | 已自动验证 | format/lint/typecheck/build 通过；Vitest 与 coverage 均为 18 files/83 tests；public/user/admin/Agent client drift 均通过 |
| mocked Playwright | 已自动验证 | Chromium + mobile Chromium，6/6 |
| real Playwright | 已自动验证 | Chromium 14 + mobile Chromium 5，共 19/19；`target/task19-e2e/hotshop-task19-5f4685103fcf/` |
| TASK-19 故障矩阵 | 已自动验证 | 7/7，两个 gate 均 PASSED；`target/task19-faults/hotshop-task19-06baf570ef34/fault-matrix.json` |
| TASK-19 scanners 与 ZAP | 已自动验证 | 19 gates PASSED，未处理 High/Critical=0；ZAP 0 FAIL/7 WARN；`target/task19-security/hotshop-task19-11634a804f63/summary.json` |
| Docker 清理 | 已自动验证 | 三个正式入口 `cleanup.clean=true`，各自容器/卷/网络/镜像/临时密钥残留 0；非本轮资源 ID 集合不变 |
| 威胁模型与边界说明 | 只静态审查 | 证据映射与剩余风险见 `docs/security/threat-model.md`；自动回归由上方 Java/Agent/Web/E2E/scanner gate 提供 |
| TASK-20 压测、TASK-21 README/简历改写、真实模型 Provider | 未执行 | 明确不在 TASK-19 范围；CI/验收固定 FakeModel 与 deterministic embedding |

## 静态审查结论

- Agent JSON API 由 FastAPI runtime OpenAPI baseline 重复生成；SSE 独立解析器对 event/data 字段、UUID、
  run/session/message 绑定、sequence、citation 和 Content-Type 做严格 guard。
- Access Token 沿用内存 Auth Store；Session/Run/确认值没有 storage 写入。确认值只在点击处理函数的局部
  变量中从签发传到消费，不进入 React state、ref、DOM、日志、SSE 或 artifact。
- AbortController、route unmount cleanup、run cancellation 与 generation 隔离共同阻止迟到事件污染新请求。
- Administrator registry 只含统计、异常和低风险配置草稿；退款、补偿、Outbox replay、封禁、权限和密钥
  请求由 server-owned route 固定拒绝。
- 浏览器 `/agent-api` 代理先于 `/api`，目标是显式 Agent base URL；没有启用 wildcard CORS。

## 已知限制

- 依赖和镜像漏洞结论会受扫描数据库时间点影响，必须保存工具版本和机器 JSON。
- ZAP 2.16.1 baseline 返回 exit 2：0 FAIL、7 类 WARN；WARN 是静态资源缓存/Server header、CSP
  `style-src unsafe-inline`、Spectre isolation、现代 Web 应用识别、可疑压缩注释及固定版本提示，不是
  High/Critical 漏洞豁免。机器报告保存在 security evidence 下。
- Docker Desktop 在本轮观察到少量 Python/Node/Temurin launcher 原生 exit 139；只有在测试逻辑启动前、
  具备明确原生崩溃签名时才重跑，原始失败仍保留。最终正式故障矩阵的 Java gate 首次尝试即成功。
- Security working-tree 扫描从 Git tracked 与非忽略 untracked 文件生成独立只读快照，避免把忽略的历史
  `target/` 机器报告再次当成源码；不会排除生产源码。快照与 scanner cache 均按本轮身份精确清理。
- `hotshop-task19-pnpm` volume 无 owner label 且早于本轮，无法证明为本轮创建，按资源安全规则保留。
- TASK-19 验证恢复正确性，不给出吞吐、并发容量或生产 SLA 结论。
