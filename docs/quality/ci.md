# HotShop GitHub Actions CI

本页描述 TASK-18 建立并由 TASK-19 扩展的持续集成边界。`dc779d5` 对应的托管 CI 全部通过只属于
TASK-18 历史基线；当前未提交的 TASK-19 workflow 改动已在 Docker 内做本地策略与底层命令验证，尚无
GitHub 托管运行，不把历史运行冒充本工作树结果。

## 工作流与职责

快速工作流是 [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)，在 Pull Request 和
`master` push 上运行。`Change detection` 对 Java/Maven、Agent/Python、Web、OpenAPI、
Docker/Compose、CI 配置和纯文档做 fail-safe 分类：公共配置、根 POM、Compose、Docker 或 workflow
变更会选择全套门禁；未知根路径也选择全套门禁；纯文档可以只运行始终执行的 CI/安全检查和最终
总门禁。`Required CI gate` 读取每个被选择 job 的真实 result，被选择 job 如果 failure、cancelled
或意外 skipped 均失败，因此不会出现“昂贵 job 全部 skipped 仍假绿色”。

快速 job：

- `CI and security policy`：运行 CI 规则测试、自检、Compose 清理 native/所有权实测探针、actionlint、
  脱敏 Secret 扫描和固定 digest 的 TASK-19 Semgrep 本地规则；所有变更均执行。Semgrep JSON 作为
  artifact 上传，快速 Gitleaks 由脱敏退出码/Job 日志门禁；完整工作流另保存两份 Gitleaks JSON。
- `Java 21 verify`：Temurin 21、Maven Wrapper、Maven cache、`./mvnw -B -ntp clean verify`、
  Testcontainers、Surefire/Failsafe XML 和 JaCoCo 基线。
- `Agent deterministic gate`：从固定 Python 3.12.14 Alpine Dockerfile 构建 test image；在
  `--network none` 下运行 Ruff、format、strict mypy、非 Qdrant 全量 pytest、确定性覆盖率采样和
  quick eval；全量测试与覆盖率步骤都必须成功。
- `Web quality and mocked smoke`：Node 22、pnpm 10.15.0、frozen lockfile、格式/lint/typecheck、
  Vitest、V8 coverage、build、public/user/admin/Agent client drift，以及现有 mocked `smoke.spec.ts`。
- `OpenAPI compatibility and drift`：Ubuntu Runner 的 `pwsh` 执行现有运行时 OpenAPI 脚本，验证
  public/user/admin/Agent baseline compatibility、生成客户端 drift 和工作树无生成差异。Agent baseline
  直接由 FastAPI `create_app().openapi()` 在固定 test image 中生成。
- `Docker reproducible builds`：Compose config，Java runtime、Agent runtime/test、Web image 构建，
  并断言关键 runtime 配置为非 root；只构建、不推送、不上传 image tar。
- `Required CI gate`：始终执行并聚合以上真实结果。

完整工作流是 [`.github/workflows/full-verification.yml`](../../.github/workflows/full-verification.yml)，支持
手动触发并按 UTC cron 每周运行。它不使用模型 Secret，以
FakeModel 和 deterministic embedding 串行运行真实 Qdrant 全量 pytest 与 full eval；Qdrant 固定
为 v1.15.4 并固定 digest，位于独立 internal Docker network，pytest 和 full eval 不会并行重建同一 alias。它还执行
完整 Java、Web/OpenAPI、所有应用镜像构建、隔离 core Compose readiness，以及既有 TASK-16
全服务 Compose smoke。TASK-19 增加三个独立且都有 timeout/artifact 的 job：真实 Desktop/mobile
E2E、故障恢复矩阵，以及 Gitleaks/OSV/Trivy/Semgrep/ZAP 与最终镜像扫描；三者失败都传递到最终 gate。
TASK-16 脚本先拒绝
任何同项目标签资源或 owned image tag 冲突，再分别记录本轮实际创建的容器、network、volume、临时
密钥目录和镜像 ID；finally 只清理这些已记录对象并汇总清理错误。

## 模型与网络安全证据

两个 workflow 都显式设置 `AGENT_MODEL_PROVIDER=fake` 和
`AGENT_EMBEDDING_PROVIDER=deterministic`，不注入 DeepSeek、Qwen、百炼或 GitHub Token 到业务命令。
快速 Agent 的非 Qdrant pytest 与 quick eval 使用 Docker `--network none`；真实 Provider contract
仍通过现有 `MockTransport` 测试。手动 full 只让 Agent test container 接入 internal Qdrant network，
因此可访问 Qdrant，但不能访问公网付费模型。

## 缓存和 artifact

Java 使用 `setup-java` Maven cache；Web 使用 `setup-node` pnpm cache；Docker job 使用 BuildKit GHA
cache。缓存只加速依赖和层复用，工作流仍从全新 checkout 构建，不依赖仓库内的 `target`、
`node_modules` 或旧 Docker volume。

报告名包含 `run_id` 和 `run_attempt`。Java XML/JaCoCo、Agent JUnit/coverage/eval、Web coverage、
OpenAPI 脱敏日志保留 7 天。Playwright HTML report、test-results、trace 和 screenshot 只在失败时上传，
保留 3 天。上传步骤使用 `if: always()` 或 `if: failure()`，但测试本身不使用
`continue-on-error`。artifact 缺失只产生上传警告；它不会改变前面测试命令的失败结果。workflow
不上传 `.env`、私钥、Token、Docker volume、数据库 dump、完整 workspace 或浏览器 profile。

## Branch Protection

建议把以下稳定 check 设为 `master` 的 required check：

- `Required CI gate`

这个最终 check 已强制 `CI and security policy`，并依据可信变更检测强制所有本次相关 job。若组织策略
希望在 GitHub UI 中分别展示固定门禁，也可额外要求 `CI and security policy`，但不要把条件执行的
Java/Agent/Web/OpenAPI/Docker job 单独设为 required，否则纯文档 PR 的预期 skip 会阻塞合并。
手动的 `Full verification gate` 不应设成每个 PR 的 required check。

## Docker 本地复现

仓库的工具链验证可完全通过 Docker 执行：

```bash
docker run --rm -v "$PWD:/repo" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /repo eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 \
  ./mvnw -B -ntp clean verify

docker build --target test -t hotshop-agent:ci-test -f agent/Dockerfile agent
docker run --rm --network none --entrypoint python hotshop-agent:ci-test -m ruff check .
docker run --rm --network none --entrypoint python hotshop-agent:ci-test -m ruff format --check .
docker run --rm --network none --entrypoint python hotshop-agent:ci-test -m mypy --no-incremental src tests
docker run --rm --network none -e PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
  -e AGENT_MODEL_PROVIDER=fake -e AGENT_EMBEDDING_PROVIDER=deterministic \
  --entrypoint python hotshop-agent:ci-test -m pytest -m 'not qdrant' \
  -p pytest_asyncio.plugin -p no:cacheprovider

# coverage 命令在上述显式 asyncio 插件之外只增加 pytest-cov：
# -p pytest_cov.plugin --cov=hotshop_agent --cov-report=xml --cov-report=html
# quick/full eval 同样设置 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1；full 只连接 internal Qdrant network。

docker run --rm -v "$PWD:/repo" -w /repo/web \
  mcr.microsoft.com/playwright:v1.57.0-noble@sha256:3bed4b1a12f2338642f3d8cba28e291deef3c66bd4a964bbeb3e57bbff511dbd \
  bash -lc \
  'corepack enable && corepack prepare pnpm@10.15.0 --activate && pnpm install --frozen-lockfile && pnpm check && CI=true pnpm exec playwright test e2e/smoke.spec.ts'

docker compose --env-file .env.example config --quiet
docker run --rm -v "$PWD:/repo" -w /repo \
  rhysd/actionlint@sha256:887a259a5a534f3c4f36cb02dca341673c6089431057242cdc931e9f133147e9
docker run --rm -v "$PWD:/repo" -w /repo \
  zricethezav/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854 \
  git --redact --config .gitleaks.toml --log-opts=HEAD --no-banner
```

Windows PowerShell 用 `${PWD}` 替换 `$PWD`，Docker Desktop 的嵌套 Testcontainers 验证还需要挂载
`/var/run/docker.sock`；必要时设置 `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`。

## 已知限制

- GitHub 托管 Runner 只提供功能持续验证，绝不作为 10k VU、吞吐或延迟的正式性能结论。
- 快速 Playwright 仍是已有 mocked smoke，不冒充真实后端 E2E；重型真实浏览器、故障注入、ZAP 和
  镜像扫描只在完整工作流运行。
- JaCoCo、Python 和 Web coverage 当前只记录真实基线，不设置未经测量论证的阈值。Python JUnit
  来自完整选定测试集；coverage 来自稳定的核心 RAG/embedding（full workflow 另含 Qdrant）采样，
  不能把该覆盖率 artifact 解释为全套 Python 测试的覆盖率。
- Java 门禁显式要求 `portal`、`admin` 和 `task` 生成非空 `jacoco.xml`；`portal` 的测试 JVM 参数通过
  Surefire late property evaluation 与 JaCoCo agent 合并。`infrastructure` 当前没有测试源码，因此不会
  生成可解释的独立 JaCoCo 报告；这是已记录的覆盖基础设施缺口，不代表该模块已获得测试覆盖，也不以
  空报告或虚构阈值制造绿色。
- Dependabot 对 GitHub Actions、Maven、Web npm/pnpm 和 Agent pip 做分组更新并限制并发 PR；更新仍须通过
  相同门禁。
- workflow 的实际托管环境结论以 GitHub Actions 运行记录为准；`dc779d5` 对应 TASK-18 的全部 Job 和
  `Required CI gate` 均通过，TASK-19 当前工作树尚未产生托管运行。

## TASK-19 当前增量

TASK-19 为消除扫描出的 High/Critical，当前使用 Spring Boot 3.5.16、Tomcat 10.1.59、Netty
4.1.138.Final、RabbitMQ Java Client 5.33.1、Python 3.12.14 Alpine 和 Playwright 1.57.0。Web 最终
runtime 是固定 digest 的 `nginx-unprivileged`，镜像配置用户为 `101:101`；Agent root 入口只复制
service key，随后以 UID/GID 10001、清空附加组和 `NoNewPrivs=1` 执行业务进程。完整 E2E 同时启动最终
Nginx，实测 CSP/nosniff、Portal 与 Agent 明确代理，ZAP 也扫描该最终入口。

## TASK-18 reconciliation 本地验证基线

以下数字来自 2026-08-12 至 2026-08-13 在 Docker 中执行的工作树验证，不代表 GitHub 托管 Runner
已经运行：

- Java `clean verify`：290 项测试，0 failure、0 error、0 skipped；`portal`、`admin`、`task` 的
  `jacoco.xml` 均存在且非空。effective POM 同时包含 JaCoCo 0.8.13 注入和
  `@{argLine} -XX:TieredStopAtLevel=1`。
- Agent：当前固定 Python 3.12.14 test image 的 strict mypy 检查 53 个源文件且无问题；
  `--network none` 的非 Qdrant pytest 为 250 passed、6 skipped、8 deselected；quick eval 为 28/28；
  独立 internal network 的真实 Qdrant 重点集为 36 passed，full eval 为 29/29。pytest 禁止自动加载入口点
  插件：普通/Qdrant 测试实际只显式加载 `pytest_asyncio.plugin`，coverage 额外显式加载
  `pytest_cov.plugin`；LangSmith pytest 插件未加载。上述过程只使用 FakeModel 和 deterministic
  embedding，非 Qdrant 测试与 quick eval 使用 `--network none`。
- Web：Vitest 83/83，coverage 同样执行 83 项；format、lint、typecheck、build 和
  public/user/admin/Agent 客户端 drift 均通过；mocked Playwright desktop/mobile 共 6/6。当前 V8
  coverage 总行基线为 26.70%，尚未
  设置阈值。
- OpenAPI：运行时生成 public、user、mock-provider-callback、admin 四份文档；public、user、admin
  compatibility 和客户端 drift 均通过。
- 容器：Portal、Admin、Task、Agent runtime/test、Web 均完成重建；Portal/Admin/Task 最终用户为
  `10001:10001`，Agent 为 `10001`，Web 为 `pwuser`。TASK-16 Compose 会验证 Portal/Admin/Agent
  业务 PID 为 UID 10001，Java 私钥为 `10001:10001`、模式 `0400`，owner 可读且 UID 10002 不可读；
  本轮动态结果见下一项。
- Compose 清理 native wrapper 在固定 PowerShell 7.4 容器通过：stderr 有正常输出且 exit 0、非零
  exit 被记录、包含空格和路径的参数逐字一致、审计写入异常被隔离、前一步失败后后续步骤继续；探针
  通过唯一临时 `.ps1` 文件和 `-File` 执行，不向 native `-Command` 传递带引号脚本文本。所有权探针
  还实际创建带唯一 project label
  的哨兵容器、卷、网络和三个同名镜像 tag；验证脚本在前置阶段拒绝后，容器/网络/镜像 ID 与卷
  inspect 指纹均不变，审计记录没有 `compose down` 或 Docker `rm`，且空 ownership mode 未作为空
  native 参数传递；无冲突前置检查放行，模拟业务启动后失败仅清理本轮记录资源。最终清理使用无
  `--format` 的 Docker inspect JSON，并且 ownership inspect、删除及后置验证都通过不外抛的 cleanup
  wrapper；注入一次 inspect JSON 解析异常后，后续精确资源仍被清理，输出同时保留原始业务失败与
  附加清理汇总。RECONCILE-04 的完整 TASK-16 Compose 在固定 PowerShell 7.4 和
  固定 Docker CLI/Compose 组合镜像中运行一次并 exit 0；登录、Agent 工具边界、购买确认、防重放与
  数据库审计断言均通过。退出后按精确 project label 和 image tag 检查为 0 个容器、0 个卷、0 个
  网络、0 个 owned image，临时私钥目录为 0。同一工作树还在本机 Windows PowerShell 5.1 下让
  native cleanup 探针连续三次 exit 0、ownership 探针 exit 0，并完整执行 TASK-16 Compose 至 exit 0；
  该次完整业务验证同样由脚本自动清理至容器、卷、网络、owned image 和临时私钥目录全部为 0。
- CI 自检：43 项规则/变更检测/final-gate 单测通过（包含两个 workflow 的清理探针防移除规则，以及
  禁止 final cleanup 直接 native 调用、inspect format 和 native `-Command` 的回归规则）；
  actionlint、Compose config、`git diff --check`、Gitleaks working tree、完整 HEAD history 和两个运行时
  拼接的 synthetic negative case 均通过。这里不硬编码提交历史数量。

本地 Docker Desktop 曾在旧 Agent 缓存镜像上出现随机 Python 进程崩溃。本轮不沿用该旧镜像；以上
数字仅描述本轮无缓存镜像的五次 pytest、一次 Qdrant pytest 和两组 eval 实际结果，不推断超出样本的
长期稳定性。GitHub Actions 托管运行已验证工作流编排和最终门禁，不能替代更长期的稳定性观察。
